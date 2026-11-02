#!/usr/bin/env bash
# Build once and upload Java code-only functions, without Docker or fn deploy.
set -euo pipefail

source "${TRAFICINFO_ROOT:?Run source ./scripts/setup.sh first}/scripts/oci-common.sh"

root_dir="${TRAFICINFO_ROOT:?Run source ./scripts/setup.sh first}"
app_name="${OCI_FN_APPLICATION_NAME:?Run source ./scripts/setup.sh first}"
runtime_name=java21.ol9
output_bucket="${BUCKETNAME_OUT:-traficinfo}"
osm_bucket="${BUCKETNAME_OSM:-osminfo}"
jar_file="$root_dir/dsfunctions/target/dsfunctions-1.0-SNAPSHOT-jar-with-dependencies.jar"

# Java handlers. Data source resource names and buckets come from dsconfig.json.
functions=(
  'OSMInfoFunction|osm|org.traficinfo.dsfunctions.OSMInfoFunction::handleRequest'
  'DataSourceFunction|ds|org.traficinfo.dsfunctions.DataSourceFunction::handleRequest'
  'PreprocessingFunction|preprocess|org.traficinfo.dsfunctions.PreprocessingFunction::handleRequest'
  'MMM|geojson|org.traficinfo.dsfunctions.m3m.GeoJsonOutFunction::handleRequest'
  'DPT34|geojson|org.traficinfo.dsfunctions.dpt34.GeoJsonOutFunction::handleRequest'
  'DPT30|geojson|org.traficinfo.dsfunctions.dpt30.GeoJsonOutFunction::handleRequest'
  'DPT76|geojson|org.traficinfo.dsfunctions.dpt76.GeoJsonOutFunction::handleRequest'
  'MRN|geojson|org.traficinfo.dsfunctions.mrn.GeoJsonOutFunction::handleRequest'
  'NIM|geojson|org.traficinfo.dsfunctions.nim.GeoJsonOutFunction::handleRequest'
)

usage() {
  printf 'Usage: %s [ds | preprocess | geojson <data-source-id> | osm]\n' "$0" >&2
}

select_functions() {
  if [ "$#" -eq 0 ]; then return; fi
  local selected_type="$1" selected_source='' definition data_source_id type handler
  local selected_functions=()
  case "$selected_type" in
    preprocess)
      if [ "$#" -ne 1 ]; then usage; return 1; fi
      functions=('PreprocessingFunction|preprocess|org.traficinfo.dsfunctions.PreprocessingFunction::handleRequest')
      return
      ;;
    ds)
      if [ "$#" -gt 2 ]; then usage; return 1; fi
      # Accept the old source selector, but always deploy the complete shared registry.
      if [ "$#" -eq 2 ]; then get_data_source "$2" >/dev/null || return; fi
      functions=('DataSourceFunction|ds|org.traficinfo.dsfunctions.DataSourceFunction::handleRequest')
      return
      ;;
    geojson)
      if [ "$#" -ne 2 ]; then usage; return 1; fi
      local selected_config
      selected_config="$(get_data_source "$2")" || return
      data_source_function_name "$selected_config" "$selected_type" >/dev/null || return
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
  mvn -f "$root_dir/pom.xml" -pl dsfunctions -am -DskipTests \
    "-Ddatasource.config.directory=$(dirname "$TRAFICINFO_DSCONFIG")" package
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
  if ! jar tf "$jar_file" | grep -Fx 'dsconfig.json' >/dev/null; then
    printf 'The fat JAR does not contain bundled dsconfig.json.\n' >&2
    return 1
  fi
  jar_bytes="$(wc -c < "$jar_file" | tr -d '[:space:]')"
  if [ "$jar_bytes" -gt 25000000 ]; then
    printf 'Direct archive upload is limited to 25 MB; this JAR is %s bytes.\n' "$jar_bytes" >&2
    return 1
  fi
}

build_function_config() {
  local type="$1" namespace="$2" source="$3" input config
  if [ "$type" = osm ]; then
    validate_bucket_name "$osm_bucket" BUCKETNAME_OSM || return
    jq -cne --arg namespace "$namespace" --arg bucket "$osm_bucket" \
      --arg max_loop "${MAX_LOOP_DURATION_SECONDS-180}" --arg max_way "${MAX_WAY_MAPPINGS_PER_RUN-12}" '
      def positive_integer:
        test("^[1-9][0-9]*$") and (tonumber <= 2147483647);
      if ($max_loop | positive_integer) and ($max_way | positive_integer) then
        {NAMESPACE: $namespace, BUCKETNAME_OSM: $bucket, MAX_LOOP_DURATION_SECONDS: $max_loop, MAX_WAY_MAPPINGS_PER_RUN: $max_way}
      else error("MAX_LOOP_DURATION_SECONDS and MAX_WAY_MAPPINGS_PER_RUN must be positive integers fitting in a Java int") end'
    return
  fi
  if [ "$type" = ds ] || [ "$type" = preprocess ]; then
    jq -cn --arg namespace "$namespace" '{NAMESPACE: $namespace}'
    return
  fi
  input="$(jq -r '.inputBucket' <<< "$source")"
  validate_bucket_name "$input" inputBucket || return
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

# OCI counts UTF-8 bytes in configuration keys and values, not JSON transport escaping.
configuration_size_bytes() {
  jq -ne --argjson config "$1" --argjson application "${2:-null}" '
    def string_map:
      if type != "object" then error("Expected a configuration object")
      elif any(.[]; type != "string") then error("Configuration values must be strings")
      else . end;
    (($application // {} | string_map) + ($config | string_map))
    | [to_entries[] | (.key | utf8bytelength) + (.value | utf8bytelength)]
    | add // 0
  '
}

validate_configuration_size() {
  local bytes
  bytes="$(configuration_size_bytes "$1" "${3:-null}")" || return
  if [ "$bytes" -gt 4096 ]; then
    printf '%s configuration uses %s UTF-8 bytes; OCI limit is 4096 (including application settings).\n' \
      "$2" "$bytes" >&2
    return 1
  fi
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
  local timeout="${6:-30}"
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
  if [ "$function_name" = OSMInfoFunction ]; then
    options+=(--timeout-in-seconds 300)
  elif [ -n "${6:-}" ]; then
    options+=(--timeout-in-seconds "$6")
  fi
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
  local data_source_id type tags sources source application_config check_index timeout index=0
  local function_ids=() function_names=() configs=() defined_tags=() timeouts=()
  select_functions "$@"
  sources="$(load_data_sources)"
  if [ "${TRAFICINFO_DSCONFIG##*/}" != dsconfig.json ]; then
    log 'The bundled datasource configuration must be named dsconfig.json.'
    return 1
  fi
  while IFS= read -r source; do
    data_source_settings "$source" >/dev/null
    if [[ " ${functions[*]} " == *"|preprocess|"* ]]; then preprocessing_settings "$source" >/dev/null; fi
  done < <(jq -c '.[]' <<< "$sources")
  # Prepare all configs and tags before any build or OCI mutation.
  for definition in "${functions[@]}"; do
    IFS='|' read -r data_source_id type handler <<< "$definition"
    if [ "$type" = osm ]; then
      function_name="$data_source_id"
      source='{"id":"OSMInfoFunction","schedule":"Hourly"}'
    elif [ "$type" = ds ] || [ "$type" = preprocess ]; then
      function_name="$data_source_id"
      source='{}'
    else
      source="$(get_data_source "$data_source_id" "$sources")"
      function_name="$(data_source_function_name "$source" "$type")"
    fi
    namespace="${OCI_OBJECT_STORAGE_NAMESPACE:?Run source ./scripts/setup.sh first}"
    config="$(build_function_config "$type" "$namespace" "$source")"
    validate_configuration_size "$config" "$function_name"
    tags=''
    if [ "$type" = ds ] || [ "$type" = preprocess ]; then tags='{}'; fi
    if [ "$type" = osm ]; then tags="$(schedule_tags "$source")"; fi
    function_names+=("$function_name")
    configs+=("$config")
    defined_tags+=("$tags")
    timeout=''
    if [ "$type" = ds ]; then timeout="$(jq -r '[.[] | .datasets[.activeDatasetId].timeoutSeconds // 30] | max' <<< "$sources")"; fi
    if [ "$type" = preprocess ]; then timeout=300; fi
    timeouts+=("$timeout")
  done
  application_id="$(resolve_application_id)"
  application_config="$(oci fn application get --application-id "$application_id" \
    | jq -ce '.data.config // {}')"
  for ((check_index = 0; check_index < ${#configs[@]}; check_index++)); do
    validate_configuration_size "${configs[$check_index]}" "${function_names[$check_index]}" "$application_config"
  done
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
      work_request_id="$(update_archive_function "$function_id" "$function_name" "$handler" "${configs[$index]}" "${defined_tags[$index]}" "${timeouts[$index]}")"
    else
      work_request_id="$(create_archive_function "$application_id" "$function_name" "$handler" "${configs[$index]}" "${defined_tags[$index]}" "${timeouts[$index]}")"
    fi
    wait_for_work_request "$work_request_id"
    index=$((index + 1))
  done
}

main "$@"
