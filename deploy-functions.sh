#!/usr/bin/env bash
# Build once and upload Java code-only functions, without Docker or fn deploy.
set -euo pipefail

source "${TRAFICINFO_ROOT:?Run source ./setup.sh first}/oci-common.sh"

root_dir="${TRAFICINFO_ROOT:?Run source ./setup.sh first}"
app_name="${OCI_FN_APPLICATION_NAME:?Run source ./setup.sh first}"
runtime_name=java21.ol9
output_bucket="${BUCKETNAME_OUT:-traficinfo}"
osm_bucket="${BUCKETNAME_OSM:-osminfo}"
jar_file="$root_dir/dsfunctions/target/dsfunctions-1.0-SNAPSHOT-jar-with-dependencies.jar"

# Java handlers. Data source resource names and buckets come from dsconfig.json.
functions=(
  'OSMInfoFunction|osm|org.traficinfo.dsfunctions.OSMInfoFunction::handleRequest'
  'MMM|ds|org.traficinfo.dsfunctions.m3m.DataSourceFunction::handleRequest'
  'DPT34|ds|org.traficinfo.dsfunctions.dpt34.DataSourceFunction::handleRequest'
  'DPT30|ds|org.traficinfo.dsfunctions.dpt30.DataSourceFunction::handleRequest'
  'MMM|geojson|org.traficinfo.dsfunctions.m3m.GeoJsonOutFunction::handleRequest'
  'DPT34|geojson|org.traficinfo.dsfunctions.dpt34.GeoJsonOutFunction::handleRequest'
  'DPT30|geojson|org.traficinfo.dsfunctions.dpt30.GeoJsonOutFunction::handleRequest'
)

usage() {
  printf 'Usage: %s [<ds|geojson> <data-source-id> | osm]\n' "$0" >&2
}

select_functions() {
  if [ "$#" -eq 0 ]; then return; fi
  local selected_type="$1" selected_source='' definition data_source_id type handler
  local selected_functions=()
  case "$selected_type" in
    ds|geojson)
      if [ "$#" -ne 2 ]; then usage; return 1; fi
      get_data_source "$2" >/dev/null || return
      selected_source="$2"
      ;;
    osm)
      if [ "$#" -ne 1 ]; then usage; return 1; fi
      ;;
    *) usage; return 1 ;;
  esac
  for definition in "${functions[@]}"; do
    IFS='|' read -r data_source_id type handler <<< "$definition"
    if [ "$type" = "$selected_type" ] && { [ "$type" = osm ] || [ "$data_source_id" = "$selected_source" ]; }; then
      selected_functions+=("$definition")
    fi
  done
  if [ "${#selected_functions[@]}" -ne 1 ]; then
    printf 'No unique deployment handler for %s %s.\n' "$selected_type" "$selected_source" >&2
    return 1
  fi
  functions=("${selected_functions[@]}")
}

build_jar() {
  printf 'Building %s\n' "$jar_file"
  mvn -f "$root_dir/pom.xml" -pl dsfunctions -am -DskipTests package
}

validate_jar() {
  local jar_bytes definition data_source_id type handler handler_class
  if [ ! -s "$jar_file" ]; then
    printf 'The fat JAR was not produced: %s\n' "$jar_file" >&2
    return 1
  fi
  for definition in "${functions[@]}"; do
    IFS='|' read -r data_source_id type handler <<< "$definition"
    handler_class="${handler%%::*}"
    handler_class="${handler_class//.//}.class"
    if ! jar tf "$jar_file" | grep -Fx "$handler_class" >/dev/null; then
      printf 'The fat JAR does not contain handler %s for %s.\n' "$handler" "$data_source_id/$type" >&2
      return 1
    fi
  done
  jar_bytes="$(wc -c < "$jar_file" | tr -d '[:space:]')"
  if [ "$jar_bytes" -gt 25000000 ]; then
    printf 'Direct archive upload is limited to 25 MB; this JAR is %s bytes.\n' "$jar_bytes" >&2
    return 1
  fi
}

input_bucket_for_source() {
  local source="$1" id bucket
  id="$(jq -r '.id' <<< "$source")"
  bucket="$(jq -r '.inputBucket' <<< "$source")"
  # Retain the existing deployment-specific environment overrides.
  case "$id" in
    MMM) bucket="${BUCKETNAME_IN:-$bucket}" ;;
    DPT34) bucket="${BUCKETNAME_DPT34:-$bucket}" ;;
    DPT30) bucket="${BUCKETNAME_DPT30:-$bucket}" ;;
  esac
  validate_bucket_name "$bucket" "$id" || return
  printf '%s\n' "$bucket"
}

build_function_config() {
  local type="$1" namespace="$2" source="$3" input config
  if [ "$type" = osm ]; then
    validate_bucket_name "$osm_bucket" BUCKETNAME_OSM || return
    jq -cne --arg namespace "$namespace" --arg bucket "$osm_bucket" \
      --arg max_loop "${MAX_LOOP_DURATION_SECONDS-120}" --arg max_way "${MAX_WAY_MAPPINGS_PER_RESOURCE-5}" '
      def positive_integer:
        test("^[1-9][0-9]*$") and (tonumber <= 2147483647);
      if ($max_loop | positive_integer) and ($max_way | positive_integer) then
        {NAMESPACE: $namespace, BUCKETNAME_OSM: $bucket, MAX_LOOP_DURATION_SECONDS: $max_loop, MAX_WAY_MAPPINGS_PER_RESOURCE: $max_way}
      else error("MAX_LOOP_DURATION_SECONDS and MAX_WAY_MAPPINGS_PER_RESOURCE must be positive integers fitting in a Java int") end'
    return
  fi
  input="$(input_bucket_for_source "$source")" || return
  if [ "$type" = ds ]; then
    jq -cn --arg namespace "$namespace" --arg bucket "$input" \
      '{NAMESPACE: $namespace, BUCKETNAME: $bucket}'
    return
  fi
  validate_bucket_name "$output_bucket" BUCKETNAME_OUT || return
  config="$(jq -cn --arg namespace "$namespace" --arg input "$input" --arg output "$output_bucket" \
    '{NAMESPACE: $namespace, BUCKETNAME_IN: $input, BUCKETNAME_OUT: $output}')" || return
  validate_bucket_name "$osm_bucket" BUCKETNAME_OSM || return
  local id object mapping
  id="$(jq -r '.id' <<< "$source")"
  object="${OSM_CACHE_OBJECT:-${id}_OSMInfo.json}"
  mapping="${OSM_WAY_MAPPING_OBJECT:-${id}_OSMWayMapping.json}"
  config="$(jq -c --arg bucket "$osm_bucket" --arg object "$object" --arg mapping "$mapping" \
    '. + {BUCKETNAME_OSM: $bucket, OSM_CACHE_OBJECT: $object, OSM_WAY_MAPPING_OBJECT: $mapping}' \
    <<< "$config")" || return
  printf '%s\n' "$config"
}

schedule_tags() {
  jq -ce '
    .schedule as $schedule
    | if (["Hourly", "Daily", "Weekly", "Monthly"] | index($schedule)) == null then
        error("Invalid or missing schedule for data source \(.id)")
      else {TraficInfo: {DataSourceSchedule: $schedule}} end
  ' <<< "$1"
}

create_archive_function() {
  local application_id function_name handler config
  application_id="$1"
  function_name="$2"
  handler="$3"
  config="$4"
  local timeout=30
  if [ "$function_name" = OSMInfoFunction ]; then timeout=300; fi
  local options=(--query '"opc-work-request-id"' --raw-output)
  if [ -n "$5" ]; then options+=(--defined-tags "$5"); fi
  printf 'Creating archive function %s in %s\n' "$function_name" "$app_name" >&2
  oci fn function create archive-function direct-archive fn-update-runtime-config \
    --application-id "$application_id" --display-name "$function_name" \
    --archive-file "$jar_file" --functions-runtime-name "$runtime_name" \
    --handler "$handler" --memory-in-mbs 2048 --timeout-in-seconds "$timeout" \
    --config "$config" "${options[@]}"
}

update_archive_function() {
  local function_id function_name handler config
  function_id="$1"
  function_name="$2"
  handler="$3"
  config="$4"
  local options=(--force --query '"opc-work-request-id"' --raw-output)
  if [ "$function_name" = OSMInfoFunction ]; then options+=(--timeout-in-seconds 300); fi
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
  local application_id function_id work_request_id definition function_name handler namespace config
  local data_source_id type tags sources source index=0
  local function_ids=() function_names=() configs=() defined_tags=()
  select_functions "$@"
  sources="$(load_data_sources)"
  # Prepare all configs and tags before any build or OCI mutation.
  for definition in "${functions[@]}"; do
    IFS='|' read -r data_source_id type handler <<< "$definition"
    if [ "$type" = osm ]; then
      function_name="$data_source_id"
      source='{"id":"OSMInfoFunction","schedule":"Hourly"}'
    else
      source="$(get_data_source "$data_source_id" "$sources")"
      function_name="$(data_source_function_name "$source" "$type")"
    fi
    namespace="${OCI_OBJECT_STORAGE_NAMESPACE:?Run source ./setup.sh first}"
    config="$(build_function_config "$type" "$namespace" "$source")"
    tags=''
    if [ "$type" = ds ] || [ "$type" = osm ]; then tags="$(schedule_tags "$source")"; fi
    function_names+=("$function_name")
    configs+=("$config")
    defined_tags+=("$tags")
  done
  application_id="$(resolve_application_id)"
  for function_name in "${function_names[@]}"; do
    function_id="$(find_function_id "$application_id" "$function_name")"
    function_ids+=("$function_id")
  done
  build_jar
  validate_jar
  for definition in "${functions[@]}"; do
    IFS='|' read -r data_source_id type handler <<< "$definition"
    function_name="${function_names[$index]}"
    function_id="${function_ids[$index]}"
    if [ -n "$function_id" ]; then
      work_request_id="$(update_archive_function "$function_id" "$function_name" "$handler" "${configs[$index]}" "${defined_tags[$index]}")"
    else
      work_request_id="$(create_archive_function "$application_id" "$function_name" "$handler" "${configs[$index]}" "${defined_tags[$index]}")"
    fi
    wait_for_work_request "$work_request_id"
    index=$((index + 1))
  done
}

main "$@"
