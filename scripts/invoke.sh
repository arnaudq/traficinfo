#!/usr/bin/env bash
# Invoke a traffic data-source, preprocessing, GeoJSON, OSM, accident, or boundary function.
set -euo pipefail

source "${TRAFICINFO_ROOT:?Run source ./scripts/setup.sh first}/scripts/oci-common.sh"

usage() {
  printf 'Usage: %s <ds|geojson|history> <data-source-id>\n       %s preprocess <data-source-id> [archive-dataset-id]\n       %s osm\n       %s accidents [refresh]\n       %s boundaries [epci-code]\n       %s accident-publication\n' "$0" "$0" "$0" "$0" "$0" "$0" >&2
}

invoke_function() {
  local response status
  if response="$(oci fn function invoke --no-retry "$@")"; then
    if jq -e 'type == "object" and .success == false' <<< "$response" >/dev/null 2>&1; then
      log 'Function reported an application failure.'
      jq -r '.error | "\(.type): \(.message)",
        (.causes[]? | "Caused by: \(.type): \(.message)")' <<< "$response" >&2
      return 1
    fi
    printf '%s\n' "$response"
  else
    status=$?
    if [ -n "$response" ]; then printf '%s\n' "$response"; fi
    return "$status"
  fi
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
    history)
      if [ "$#" -ne 2 ]; then usage; return 1; fi
      get_data_source "$2" >/dev/null
      function_name=HistoryPublicationFunction
      body="$(jq -cn --arg id "$2" '{datasourceId: $id}')"
      invocation_options+=(--read-timeout 360)
      ;;
    accident-publication)
      if [ "$#" -ne 1 ]; then usage; return 1; fi
      function_name=AccidentPublicationFunction
      body='{}'
      invocation_options+=(--read-timeout 360)
      ;;
    boundaries)
      if [ "$#" -gt 2 ] || { [ "$#" -eq 2 ] && [[ ! "$2" =~ ^[0-9]{9}$ ]]; }; then usage; return 1; fi
      function_name=BoundaryFunction
      body='{}'
      if [ "$#" -eq 2 ]; then body="$(jq -cn --arg code "$2" '{epciCode: $code}')"; fi
      invocation_options+=(--read-timeout 360)
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
  invoke_function --function-id "$function_id" \
    "${invocation_options[@]}"
}

main "$@"
