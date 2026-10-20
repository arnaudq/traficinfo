#!/usr/bin/env bash
# Invoke a traffic data-source, GeoJSON, or OSM information function.
set -euo pipefail

source "${TRAFICINFO_ROOT:?Run source ./setup.sh first}/oci-common.sh"

usage() {
  printf 'Usage: %s <ds|geojson> <data-source-id>\n       %s osm\n' "$0" "$0" >&2
}

main() {
  local type source function_name application_id function_id
  type="${1:-}"
  case "$type" in
    ds|geojson)
      if [ "$#" -ne 2 ]; then usage; return 1; fi
      source="$(get_data_source "$2")"
      function_name="$(data_source_function_name "$source" "$type")"
      ;;
    osm)
      if [ "$#" -ne 1 ]; then usage; return 1; fi
      function_name=OSMInfoFunction
      ;;
    *) usage; return 1 ;;
  esac
  application_id="$(resolve_application_id)"
  function_id="$(resolve_function_id "$application_id" "$function_name")"
  log "Invoking $function_name..."
  exec oci fn function invoke --function-id "$function_id" \
    --fn-invoke-type sync --body "" --file "-"
}

main "$@"
