#!/usr/bin/env bash
# Build once and upload Java code-only functions, without Docker or fn deploy.
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
app_name=3MOpenDataProcessor
runtime_name=java21.ol9
input_bucket="${BUCKETNAME_IN:-ds3mbucket}"
dpt34_input_bucket="${BUCKETNAME_DPT34:-dsdpt34}"
output_bucket="${BUCKETNAME_OUT:-traficinfo}"
osm_bucket="${BUCKETNAME_OSM:-osminfo}"
osm_cache_object="${OSM_CACHE_OBJECT:-MMM_OSMInfo.json}"
jar_file="$root_dir/dsfunctions/target/dsfunctions-1.0-SNAPSHOT-jar-with-dependencies.jar"
data_source_config_file="$root_dir/dsconfig.json"

# Add functions here as name|handler|config-builder|data-source-id. Deployment mechanics
# are shared; each builder supplies the configuration needed by its handler.
functions=(
  '3MOpenDataSource|org.traficinfo.dsfunctions.m3m.DataSourceFunction::handleRequest|data_source_config|MMM'
  'DPT34OpenDataSource|org.traficinfo.dsfunctions.dpt34.DataSourceFunction::handleRequest|dpt34_data_source_config|DPT34'
  '3MGeoJsonOut|org.traficinfo.dsfunctions.m3m.GeoJsonOutFunction::handleRequest|geojson_out_config'
  'DPT34GeoJsonOut|org.traficinfo.dsfunctions.dpt34.GeoJsonOutFunction::handleRequest|dpt34_geojson_out_config'
)

resolve_application_id() {
  local application_id compartment_id
  application_id="${OCI_FN_APPLICATION_ID:-}"
  if [ -z "$application_id" ]; then
    compartment_id="${OCI_FN_COMPARTMENT_ID:-}"
    if [ -z "$compartment_id" ]; then
      compartment_id="$(fn inspect context | awk '$1 == "oracle.compartment-id:" {print $2; exit}')"
    fi
    if [ -z "$compartment_id" ]; then
      printf 'Set OCI_FN_APPLICATION_ID or configure oracle.compartment-id in the current Fn context.\n' >&2
      return 1
    fi
    application_id="$(oci fn application list --compartment-id "$compartment_id" \
      --display-name "$app_name" --all --query 'data[0].id' --raw-output)"
  fi
  case "$application_id" in
    ocid1.fnapp.*) printf '%s\n' "$application_id" ;;
    *) printf 'Could not find application %s.\n' "$app_name" >&2; return 1 ;;
  esac
}

# Function names are case sensitive.
find_function_id() {
  oci fn function list --application-id "$1" --display-name "$2" \
    --all --query 'data[0].id' --raw-output
}

build_jar() {
  printf 'Building %s\n' "$jar_file"
  mvn -f "$root_dir/pom.xml" -pl dsfunctions -am -DskipTests package
}

validate_jar() {
  local jar_bytes definition function_name handler config_builder data_source_id handler_class
  if [ ! -s "$jar_file" ]; then
    printf 'The fat JAR was not produced: %s\n' "$jar_file" >&2
    return 1
  fi
  for definition in "${functions[@]}"; do
    IFS='|' read -r function_name handler config_builder data_source_id <<< "$definition"
    handler_class="${handler%%::*}"
    handler_class="${handler_class//.//}.class"
    if ! jar tf "$jar_file" | grep -Fx "$handler_class" >/dev/null; then
      printf 'The fat JAR does not contain handler %s for %s.\n' "$handler" "$function_name" >&2
      return 1
    fi
  done
  jar_bytes="$(wc -c < "$jar_file" | tr -d '[:space:]')"
  if [ "$jar_bytes" -gt 25000000 ]; then
    printf 'Direct archive upload is limited to 25 MB; this JAR is %s bytes.\n' "$jar_bytes" >&2
    return 1
  fi
}

object_storage_namespace() {
  local namespace
  namespace="$(oci os ns get --query data --raw-output)"
  if [[ ! "$namespace" =~ ^[a-zA-Z0-9_-]+$ ]]; then
    printf 'OCI CLI returned an invalid Object Storage namespace.\n' >&2
    return 1
  fi
  printf '%s' "$namespace"
}

validate_bucket_name() {
  if [[ ! "$1" =~ ^[a-zA-Z0-9_.-]+$ ]]; then
    printf 'Set %s to a valid Object Storage bucket name.\n' "$2" >&2
    return 1
  fi
}

data_source_config() {
  validate_bucket_name "$input_bucket" BUCKETNAME_IN || return 1
  printf '{"NAMESPACE":"%s","BUCKETNAME":"%s"}' "$1" "$input_bucket"
}

dpt34_data_source_config() {
  validate_bucket_name "$dpt34_input_bucket" BUCKETNAME_DPT34 || return 1
  printf '{"NAMESPACE":"%s","BUCKETNAME":"%s"}' "$1" "$dpt34_input_bucket"
}

geojson_out_config() {
  validate_bucket_name "$input_bucket" BUCKETNAME_IN || return 1
  validate_bucket_name "$output_bucket" BUCKETNAME_OUT || return 1
  validate_bucket_name "$osm_bucket" BUCKETNAME_OSM || return 1
  jq -cn --arg namespace "$1" --arg input "$input_bucket" --arg output "$output_bucket" \
    --arg osm "$osm_bucket" --arg object "$osm_cache_object" \
    '{NAMESPACE: $namespace, BUCKETNAME_IN: $input, BUCKETNAME_OUT: $output,
      BUCKETNAME_OSM: $osm, OSM_CACHE_OBJECT: $object}'
}

dpt34_geojson_out_config() {
  validate_bucket_name "$dpt34_input_bucket" BUCKETNAME_DPT34 || return 1
  validate_bucket_name "$output_bucket" BUCKETNAME_OUT || return 1
  jq -cn --arg namespace "$1" --arg input "$dpt34_input_bucket" --arg output "$output_bucket" \
    '{NAMESPACE: $namespace, BUCKETNAME_IN: $input, BUCKETNAME_OUT: $output}'
}

schedule_tags() {
  jq -ce --arg id "$1" '
    .[$id] as $source
    | if $source.id != $id or (["Daily", "Weekly", "Monthly"] | index($source.schedule)) == null then
        error("Invalid or missing schedule for data source \($id)")
      else {TraficInfo: {DataSourceSchedule: $source.schedule}} end
  ' "$data_source_config_file"
}

create_archive_function() {
  local application_id function_name handler config
  application_id="$1"
  function_name="$2"
  handler="$3"
  config="$4"
  local options=(--query '"opc-work-request-id"' --raw-output)
  if [ -n "$5" ]; then options+=(--defined-tags "$5"); fi
  printf 'Creating archive function %s in %s\n' "$function_name" "$app_name" >&2
  oci fn function create archive-function direct-archive fn-update-runtime-config \
    --application-id "$application_id" --display-name "$function_name" \
    --archive-file "$jar_file" --functions-runtime-name "$runtime_name" \
    --handler "$handler" --memory-in-mbs 2048 --timeout-in-seconds 30 \
    --config "$config" "${options[@]}"
}

update_archive_function() {
  local function_id function_name handler config
  function_id="$1"
  function_name="$2"
  handler="$3"
  config="$4"
  local options=(--force --query '"opc-work-request-id"' --raw-output)
  if [ -n "$5" ]; then options+=(--defined-tags "$5"); fi
  printf 'Updating archive function %s (%s)\n' "$function_name" "$function_id" >&2
  oci fn function update archive-function --function-id "$function_id" \
    --archive-file "$jar_file" --handler "$handler" \
    --runtime-config FUNCTION_UPDATE --functions-runtime-name "$runtime_name" \
    --memory-in-mbs 2048 --config "$config" "${options[@]}"
}

wait_for_work_request() {
  local work_request_id status deadline
  work_request_id="$1"
  if [[ "$work_request_id" != ocid1.functionsworkrequest.* ]]; then
    printf 'OCI did not return a Functions work request OCID.\n' >&2
    return 1
  fi
  deadline=$((SECONDS + 600))

  printf 'Waiting for work request %s\n' "$work_request_id"
  while [ "$SECONDS" -lt "$deadline" ]; do
    status="$(oci fn work-request get --work-request-id "$work_request_id" \
      --query 'data.status' --raw-output)"
    case "$status" in
      SUCCEEDED)
        printf 'Work request %s succeeded.\n' "$work_request_id"
        return 0
        ;;
      FAILED|CANCELED|NEEDS_ATTENTION)
        printf 'Work request %s ended with status %s.\n' "$work_request_id" "$status" >&2
        oci fn work-request-error list --work-request-id "$work_request_id" --all >&2
        return 1
        ;;
    esac
    sleep 5
  done
  printf 'Timed out waiting for work request %s.\n' "$work_request_id" >&2
  return 1
}

main() {
  local application_id function_id work_request_id definition function_name handler config_builder namespace config
  local data_source_id tags
  local function_ids=() configs=() defined_tags=()
  local index=0
  # Validate every configured cadence before building or making OCI changes.
  for definition in "${functions[@]}"; do
    IFS='|' read -r function_name handler config_builder data_source_id <<< "$definition"
    if [ -n "$data_source_id" ]; then schedule_tags "$data_source_id" >/dev/null; fi
  done
  application_id="$(resolve_application_id)"
  namespace="$(object_storage_namespace)"
  # Prepare all configs before making changes, for both creation and updates.
  for definition in "${functions[@]}"; do
    IFS='|' read -r function_name handler config_builder data_source_id <<< "$definition"
    function_id="$(find_function_id "$application_id" "$function_name")"
    config="$("$config_builder" "$namespace")"
    tags=''
    if [ -n "$data_source_id" ]; then
      tags="$(schedule_tags "$data_source_id")"
    fi
    function_ids+=("$function_id")
    configs+=("$config")
    defined_tags+=("$tags")
  done
  build_jar
  validate_jar
  for definition in "${functions[@]}"; do
    IFS='|' read -r function_name handler config_builder data_source_id <<< "$definition"
    function_id="${function_ids[$index]}"
    if [[ "$function_id" == ocid1.fnfunc.* ]]; then
      work_request_id="$(update_archive_function "$function_id" "$function_name" "$handler" "${configs[$index]}" "${defined_tags[$index]}")"
    else
      work_request_id="$(create_archive_function "$application_id" "$function_name" "$handler" "${configs[$index]}" "${defined_tags[$index]}")"
    fi
    wait_for_work_request "$work_request_id"
    index=$((index + 1))
  done
}

main "$@"
