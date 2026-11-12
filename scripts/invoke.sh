#!/usr/bin/env bash
# Invoke a traffic data-source, preprocessing, GeoJSON, OSM information, or accident function.
set -euo pipefail

source "${TRAFICINFO_ROOT:?Run source ./scripts/setup.sh first}/scripts/oci-common.sh"

usage() {
  printf 'Usage: %s <ds|geojson> <data-source-id>\n       %s preprocess <data-source-id> [archive-dataset-id]\n       %s osm\n       %s accidents [refresh]\n' "$0" "$0" "$0" "$0" >&2
}

main() {
  local type source function_name application_id function_id timeout body=""
  local invocation_options=(--fn-invoke-type sync --file "-")
  type="${1:-}"
  case "$type" in
    ds|preprocess|geojson)
      if [ "$#" -ne 2 ] && { [ "$type" != preprocess ] || [ "$#" -ne 3 ]; }; then usage; return 1; fi
      source="$(get_data_source "$2")"
      function_name="$(data_source_function_name "$source" "$type")"
      if [ "$type" = preprocess ] || [ "$type" = geojson ]; then
        invocation_options+=(--read-timeout 360)
        body="$(jq -cn --arg id "$2" '{datasourceId: $id}')"
      fi
      if [ "$type" = preprocess ] && [ "$#" -eq 3 ]; then
        archive_preprocessing_settings "$source" "$3" >/dev/null || return
        body="$(jq -cn --arg id "$2" --arg dataset "$3" '{datasourceId: $id, datasetId: $dataset}')"
        log "Selected archive dataset $3 for one-off preprocessing."
      fi
      if [ "$type" = ds ]; then
        body="$(jq -cn --arg id "$2" '{datasourceId: $id}')"
        timeout="$(load_data_sources | jq -r '[.[] | .datasets[.activeDatasetId].timeoutSeconds // 30] | max')"
        if [ "$timeout" -gt 30 ]; then invocation_options+=(--read-timeout "$((timeout + 60))"); fi
      fi
      ;;
    accidents)
      if [ "$#" -gt 2 ] || { [ "$#" -eq 2 ] && [ "$2" != refresh ]; }; then usage; return 1; fi
      function_name=ONISRAccidentFunction
      body='{}'
      if [ "$#" -eq 2 ]; then body='{"action":"refresh"}'; fi
      invocation_options+=(--read-timeout 360)
      ;;
    osm)
      if [ "$#" -ne 1 ]; then usage; return 1; fi
      function_name=OSMInfoFunction
      body='{"mode":"manual"}'
      # Manual runs use OSMInfoFunction's MAX_LOOP_DURATION_SECONDS (not the scheduled budget).
      # Keep this client timeout aligned with that setting,
      # allowing extra time for the final batch and response (function runtime: 300 seconds).
      invocation_options+=(--read-timeout 360)
      ;;
    *) usage; return 1 ;;
  esac
  invocation_options+=(--body "$body")
  application_id="$(resolve_application_id)"
  function_id="$(resolve_function_id "$application_id" "$function_name")"
  log "Invoking $function_name..."
  exec oci fn function invoke --function-id "$function_id" \
    "${invocation_options[@]}"
}

main "$@"
