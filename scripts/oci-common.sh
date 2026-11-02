# Shared shell functions. Sourcing this file performs no initialization or OCI calls.

log() {
  local script_name="${0##*/}"
  printf '[%s] %s\n' "${script_name%.sh}" "$*" >&2
}

step() {
  current_step="$1"
  log "$current_step"
}

report_failure() {
  if [ "$1" -ne 0 ]; then
    log "Failed during: ${current_step:-initialization} (exit status $1)."
  fi
}

parse_dry_run_arguments() {
  if [ "$#" -gt 1 ]; then
    printf 'Usage: %s [--dry-run]\n' "$0" >&2
    return 1
  fi
  case "${1:-}" in
    '') printf 'false\n' ;;
    --dry-run) printf 'true\n' ;;
    *) printf 'Usage: %s [--dry-run]\n' "$0" >&2; return 1 ;;
  esac
}

# Read a JSON array; optional lookups return an empty string when absent.
unique_id() {
  jq -er --arg mode "${1:-required}" '
    if type != "array" then error("Expected a resource array")
    elif length == 0 and $mode == "optional" then ""
    elif length == 1 then .[0].id | select(type == "string" and length > 0)
    else error("Expected exactly one match, found \(length)") end
  '
}

resolve_application_id() {
  local application_id result matches
  application_id="${OCI_FN_APPLICATION_ID:-}"
  if [ -z "$application_id" ]; then
    result="$(oci fn application list --compartment-id "$OCI_COMPARTMENT_ID" \
      --display-name "$OCI_FN_APPLICATION_NAME" --all)" || return
    if [[ "$result" =~ ^[[:space:]]*$ ]]; then result='{"data":[]}'; fi
    matches="$(jq -c --arg name "$OCI_FN_APPLICATION_NAME" '
      if (.data | type) != "array" then error("Expected an application array") else . end
      |
      [.data[] | select(."display-name" == $name and ."lifecycle-state" == "ACTIVE")]
    ' <<< "$result")" || return
    application_id="$(unique_id <<< "$matches")" || return
  fi
  case "$application_id" in
    ocid1.fnapp.*) printf '%s\n' "$application_id" ;;
    *) printf 'Could not find application %s.\n' "$OCI_FN_APPLICATION_NAME" >&2; return 1 ;;
  esac
}

# Deployment may create an absent function; invoke/events require an active one.
find_function_id() {
  local application_id="$1" function_name="$2" mode="${3:-optional}" result function_id matches
  result="$(oci fn function list --application-id "$application_id" \
    --display-name "$function_name" --all)" || return
  if [[ "$result" =~ ^[[:space:]]*$ ]]; then result='{"data":[]}'; fi
  matches="$(jq -c --arg name "$function_name" --arg mode "$mode" '
    if (.data | type) != "array" then error("Expected a function array") else . end
    |
    [.data[] | select(."display-name" == $name and
      (if $mode == "required" then ."lifecycle-state" == "ACTIVE"
       else ."lifecycle-state" != "DELETED" end))]
  ' <<< "$result")" || return
  function_id="$(unique_id "$mode" <<< "$matches")" || return
  case "$function_id" in
    ocid1.fnfunc.*) printf '%s\n' "$function_id" ;;
    '') [ "$mode" = optional ] && printf '\n' ;;
    *) printf 'Invalid function OCID for %s.\n' "$function_name" >&2; return 1 ;;
  esac
}

resolve_function_id() {
  find_function_id "$1" "$2" required
}

load_data_sources() {
  jq -ce '
    def nonempty_string: type == "string" and length > 0;
    if type != "object" or length == 0 then error("Expected a nonempty data source map") else . end
    | to_entries | map(
      .key as $key | .value
      | if type != "object" then error("Invalid data source \($key)") else . end
      | if .id != $key or (.id | nonempty_string | not) then
          error("Data source key must match its id: \($key)") else . end
      | if (.id | test("^[A-Z][A-Z0-9_]*$")) then .
        else error("Data source IDs must use uppercase letters, digits and underscores: \($key)") end
      | if all([.functionPrefix, .inputBucket, .csvObject, .eventObject][];
          . == null or nonempty_string) then . else
          error("Overrides must be nonempty strings for \($key)") end
      | if .geoJsonEnabled != null and (.geoJsonEnabled | type) != "boolean" then
          error("geoJsonEnabled must be a boolean for \($key)") else . end
      | if (.activeDatasetId | nonempty_string | not) then
          error("Missing activeDatasetId for \($key)") else . end
      | if (.datasets | type) != "object" then
          error("Missing datasets for \($key)") else . end
      | .datasets[.activeDatasetId] as $dataset
      | if ($dataset | type) != "object" then
          error("Missing active dataset for \($key): \(.activeDatasetId)") else . end
      | if $dataset.timeoutSeconds != null and
          (($dataset.timeoutSeconds | type) != "number" or
           $dataset.timeoutSeconds != ($dataset.timeoutSeconds | floor) or
           $dataset.timeoutSeconds < 1 or $dataset.timeoutSeconds > 300) then
          error("Active dataset timeoutSeconds must be an integer from 1 to 300 for \($key)") else . end
      | (.functionPrefix // .id) as $prefix
      | . + {functionPrefix: $prefix,
          dataSourceFunction: "DataSourceFunction",
          geoJsonFunction: ($prefix + "GeoJsonOut"),
          preprocessingFunction: ($prefix + "Preprocessing"),
          eventRule: ($prefix + "ComptageCsvToGeoJson"),
          inputBucket: (.inputBucket // ("ds" + (.id | ascii_downcase))),
          csvObject: (.csvObject // (.id + "_Comptage.csv")),
          eventObject: (.eventObject // .csvObject // (.id + "_Comptage.csv"))}
    )
    | if (map(.eventRule) | unique | length) != length then
        error("Data sources must produce distinct Events rule names") else . end
  ' "$TRAFICINFO_DSCONFIG"
}

get_data_source() {
  local sources
  if [ "$#" -gt 1 ]; then sources="$2"; else sources="$(load_data_sources)" || return; fi
  jq -ce --arg id "$1" '
    [.[] | select(.id == $id)]
    | if length == 1 then .[0] else error("Unknown data source ID: \($id)") end
  ' <<< "$sources"
}

data_source_function_name() {
  case "$2" in
    ds) jq -er '.dataSourceFunction' <<< "$1" ;;
    preprocess) preprocessing_settings "$1" >/dev/null || return
      jq -er '.preprocessingFunction' <<< "$1" ;;
    geojson) jq -er 'if .geoJsonEnabled == false then
      error("GeoJSON is not enabled for \(.id)") else .geoJsonFunction end' <<< "$1" ;;
    *) printf 'Expected ds, preprocess or geojson.\n' >&2; return 1 ;;
  esac
}

validate_bucket_name() {
  if [[ ! "$1" =~ ^[a-zA-Z0-9_.-]+$ ]]; then
    printf 'Invalid bucket name for %s: %s.\n' "${2:-bucket}" "$1" >&2
    return 1
  fi
}

# Prepare the settings passed to a datasource function. Input order is publication order.
data_source_settings() {
  jq -ce '
    def nonempty_string: type == "string" and test("[^[:space:]]");
    . as $source
    | .datasets[.activeDatasetId] + {datasetId: .activeDatasetId}
    | if (.datasetId | nonempty_string | not) or (.inputs | type) != "array"
        or (.inputs | length) == 0 then error("Expected datasetId and source inputs") else . end
    | .inputs |= map(
        if type != "object" then error("Invalid source input") else . end
        | if (.url | type) != "string" or (.url | test("^https?://[^/[:space:]]+") | not)
            or (.contentType | nonempty_string | not) then error("Invalid input URL or content type") else . end
        | . + {objectName: (.objectName //
            (if .contentType == "text/csv" then $source.csvObject else
              error("Non-CSV inputs require an objectName") end))}
        | if (.objectName | type) != "string" or (.objectName | test("\\.(csv|json|geojson)$") | not)
            then error("Input Object name must end in .csv, .json or .geojson") else . end
      )
    | if (.inputs | map(.objectName) | unique | length) != (.inputs | length) then
        error("Duplicate input Object names") else . end
    | {dataSourceId: $source.id, datasetId, inputs}
  ' <<< "$1"
}

# Source retrieval stays unchanged; preprocessing additionally needs a dataset URL and explicit year scope.
preprocessing_settings() {
  local source="$1" settings
  settings="$(data_source_settings "$source")" || return
  jq -cne --argjson source "$source" --argjson settings "$settings" '
    $source.datasets[$source.activeDatasetId] as $dataset
    | $dataset.years as $years
    | if ($years | type) != "array" or ($years | length) == 0 then
        error("Active dataset years must be a nonempty array")
      elif ($years | unique | length) != ($years | length) then
        error("Active dataset years must be distinct")
      elif any($years[]; type != "number") then error("Invalid preprocessing year")
      elif any($years[]; . != floor or . < 1900 or . > (now | strftime("%Y") | tonumber)) then
        error("Invalid preprocessing year")
      elif ($dataset.datasetInfoUrl | type) != "string" then error("Missing active dataset datasetInfoUrl")
      elif ($dataset.datasetInfoUrl | test("^https?://[^/[:space:]]+") | not) then error("Invalid active dataset datasetInfoUrl")
      else {source: $settings, datasetUrl: $dataset.datasetInfoUrl, years: $years} end'
}

# Select an existing inactive dataset locally; never change the download configuration.
archive_preprocessing_settings() {
  local selected settings active
  selected="$(jq -ce --arg dataset "$2" '
    if .activeDatasetId == $dataset then error("Use preprocessing without a dataset ID for the active dataset")
    elif (.datasets | has($dataset) | not) then error("Unknown archive dataset: \($dataset)")
    elif any(.datasets[$dataset].inputs[]; (.objectName | type) != "string" or .objectName == "") then
      error("Archive inputs require explicit objectName values")
    else .activeDatasetId = $dataset end
  ' <<< "$1")" || return
  settings="$(preprocessing_settings "$selected")" || return
  active="$(data_source_settings "$1")" || return
  jq -cne --argjson settings "$settings" --argjson active "$active" '
    [$active.inputs[].objectName] as $names
    | if any($settings.source.inputs[]; .objectName as $name | $names | index($name) != null) then
        error("Archive inputs must not reference active input Objects")
      else $settings end'
}
