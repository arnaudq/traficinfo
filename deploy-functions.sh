#!/usr/bin/env bash
# Build once and upload Java code-only functions, without Docker or fn deploy.
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
app_name=3MOpenDataProcessor
runtime_name=java21.ol9
input_bucket="${BUCKETNAME_IN:-ds3mbucket}"
output_bucket="${BUCKETNAME_OUT:-traficinfo}"
jar_file="$root_dir/dsfunctions/target/dsfunctions-1.0-SNAPSHOT-jar-with-dependencies.jar"

# Add functions here as name|handler|creation-config-builder. Deployment mechanics
# are shared; each builder supplies the configuration needed by its handler.
functions=(
  '3MOpenDataSource|org.traficinfo.dsfunctions.m3m.DataSourceFunction::handleRequest|data_source_config'
  '3MGeoJsonOut|org.traficinfo.dsfunctions.m3m.GeoJsonOutFunction::handleRequest|geojson_out_config'
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
  local jar_bytes definition function_name handler config_builder handler_class
  if [ ! -s "$jar_file" ]; then
    printf 'The fat JAR was not produced: %s\n' "$jar_file" >&2
    return 1
  fi
  for definition in "${functions[@]}"; do
    IFS='|' read -r function_name handler config_builder <<< "$definition"
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

geojson_out_config() {
  validate_bucket_name "$input_bucket" BUCKETNAME_IN || return 1
  validate_bucket_name "$output_bucket" BUCKETNAME_OUT || return 1
  printf '{"NAMESPACE":"%s","BUCKETNAME_IN":"%s","BUCKETNAME_OUT":"%s"}' \
    "$1" "$input_bucket" "$output_bucket"
}

create_archive_function() {
  local application_id function_name handler config
  application_id="$1"
  function_name="$2"
  handler="$3"
  config="$4"
  printf 'Creating archive function %s in %s\n' "$function_name" "$app_name" >&2
  oci fn function create archive-function direct-archive fn-update-runtime-config \
    --application-id "$application_id" --display-name "$function_name" \
    --archive-file "$jar_file" --functions-runtime-name "$runtime_name" \
    --handler "$handler" --memory-in-mbs 2048 --timeout-in-seconds 30 \
    --config "$config" --query '"opc-work-request-id"' --raw-output
}

update_archive_function() {
  local function_id function_name handler
  function_id="$1"
  function_name="$2"
  handler="$3"
  printf 'Updating archive function %s (%s)\n' "$function_name" "$function_id" >&2
  oci fn function update archive-function --function-id "$function_id" \
    --archive-file "$jar_file" --handler "$handler" \
    --runtime-config FUNCTION_UPDATE --functions-runtime-name "$runtime_name" \
    --memory-in-mbs 2048 --force \
    --query '"opc-work-request-id"' --raw-output
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
  local function_ids=() configs=()
  local index=0
  application_id="$(resolve_application_id)"
  # Prepare all creation configs before making changes, so missing configuration
  # cannot leave a deployment partially applied. Existing configs are preserved.
  for definition in "${functions[@]}"; do
    IFS='|' read -r function_name handler config_builder <<< "$definition"
    function_id="$(find_function_id "$application_id" "$function_name")"
    config=''
    if [[ "$function_id" != ocid1.fnfunc.* ]]; then
      if [ -z "${namespace:-}" ]; then
        namespace="$(object_storage_namespace)"
      fi
      config="$("$config_builder" "$namespace")"
    fi
    function_ids+=("$function_id")
    configs+=("$config")
  done
  build_jar
  validate_jar
  for definition in "${functions[@]}"; do
    IFS='|' read -r function_name handler config_builder <<< "$definition"
    function_id="${function_ids[$index]}"
    if [[ "$function_id" == ocid1.fnfunc.* ]]; then
      work_request_id="$(update_archive_function "$function_id" "$function_name" "$handler")"
    else
      work_request_id="$(create_archive_function "$application_id" "$function_name" "$handler" "${configs[$index]}")"
    fi
    wait_for_work_request "$work_request_id"
    index=$((index + 1))
  done
}

main "$@"
