#!/usr/bin/env bash
# Attach the archive function to the existing Daily schedule in TraficInfo.
set -euo pipefail

compartment_name=TraficInfo
schedule_name=Daily
application_name=3MOpenDataProcessor
function_name=3MOpenDataSource
parse_arguments() {
  dry_run=false
  case "${1:-}" in
    '') ;;
    --dry-run) dry_run=true ;;
    *) printf 'Usage: %s [--dry-run]\n' "$0" >&2; return 1 ;;
  esac
  if [ "$#" -gt 1 ]; then
    printf 'Usage: %s [--dry-run]\n' "$0" >&2
    return 1
  fi
}

check_dependencies() {
  local tool
  for tool in oci jq; do
    command -v "$tool" >/dev/null 2>&1 || {
      printf '%s is required.\n' "$tool" >&2
      return 1
    }
  done
}

configure_oci() {
  export OCI_CLI_CONFIG_FILE="${OCI_CLI_CONFIG_FILE:-$HOME/.oci/config.arnaudq}"
}

# Reject missing or ambiguous names instead of selecting an arbitrary match.
unique_id() {
  jq -er 'if length == 1 then .[0].id else
    error("Expected exactly one match, found \(length)") end'
}

resolve_compartment_id() {
  oci iam compartment list \
    --compartment-id-in-subtree true --name "$compartment_name" \
    --lifecycle-state ACTIVE --all | jq '.data' | unique_id
}

resolve_application_id() {
  oci fn application list --compartment-id "$1" \
    --display-name "$application_name" --all \
    | jq '[.data[] | select(."lifecycle-state" == "ACTIVE")]' | unique_id
}

resolve_function_id() {
  oci fn function list --application-id "$1" \
    --display-name "$function_name" --all \
    | jq '[.data[] | select(."lifecycle-state" == "ACTIVE")]' | unique_id
}

resolve_schedule_id() {
  oci resource-scheduler schedule list \
    --compartment-id "$1" --display-name "$schedule_name" --all \
    | jq '[.data.items[] | select(."lifecycle-state" != "DELETED")]' | unique_id
}

get_schedule() {
  oci resource-scheduler schedule get --schedule-id "$1"
}

validate_schedule() {
  if ! jq -e --arg compartment "$2" '
    .data | ."compartment-id" == $compartment
    and ."lifecycle-state" == "ACTIVE"
    and .action == "START_RESOURCE"
    and ."recurrence-type" == "ICAL"
    and (."recurrence-details" | test("(^|;)FREQ=DAILY(;|$)"))
    and ((."resource-filters" // []) | length == 0)
  ' <<< "$1" >/dev/null; then
    printf 'Expected an active Daily ICAL start schedule with static resources in TraficInfo.\n' >&2
    return 1
  fi
}

function_is_attached() {
  jq -e --arg id "$2" 'any(.data.resources[]?; .id == $id)' \
    <<< "$1" >/dev/null
}

build_resources() {
  # OCI responses use kebab-case, whereas complex CLI input uses camelCase.
  # Convert only the parameter discriminator; payload and metadata keys are literal.
  jq -c --arg id "$2" '
    (.data.resources // [] | map(
      if .parameters != null then .parameters |= map(
        if has("parameter-type") then
          .parameterType = ."parameter-type" | del(."parameter-type")
        else . end
      ) else . end
    )) + [{id: $id, parameters: []}]
  ' <<< "$1"
}

update_schedule_resources() {
  local result
  # Replaces only resources, preserving recurrence, timezone and other fields.
  # The ETag prevents overwriting a concurrent edit to the schedule.
  result="$(oci resource-scheduler schedule update --schedule-id "$1" \
    --resources "$2" --if-match "$3" --force \
    --wait-for-state SUCCEEDED --wait-for-state FAILED --wait-for-state CANCELED \
    --max-wait-seconds 600 --wait-interval-seconds 5)"
  if ! jq -e '.data.status == "SUCCEEDED"' <<< "$result" >/dev/null; then
    printf 'Schedule update did not succeed:\n%s\n' "$result" >&2
    return 1
  fi
}

verify_attachment() {
  local schedule
  schedule="$(get_schedule "$1")"
  function_is_attached "$schedule" "$2"
}

main() {
  local dry_run compartment_id application_id function_id schedule_id
  local schedule etag resources
  parse_arguments "$@"
  check_dependencies
  configure_oci

  compartment_id="$(resolve_compartment_id)"
  application_id="$(resolve_application_id "$compartment_id")"
  function_id="$(resolve_function_id "$application_id")"
  schedule_id="$(resolve_schedule_id "$compartment_id")"
  schedule="$(get_schedule "$schedule_id")"
  validate_schedule "$schedule" "$compartment_id"
  if function_is_attached "$schedule" "$function_id"; then
    printf '%s is already attached to %s.\n' "$function_name" "$schedule_name"
    return 0
  fi

  etag="$(jq -er '.etag | select(length > 0)' <<< "$schedule")"
  resources="$(build_resources "$schedule" "$function_id")"
  printf 'Adding %s (%s) to %s (%s).\n' \
    "$function_name" "$function_id" "$schedule_name" "$schedule_id"
  if [ "$dry_run" = true ]; then
    printf 'Dry run: proposed resources (no changes submitted):\n'
    jq . <<< "$resources"
    return 0
  fi

  update_schedule_resources "$schedule_id" "$resources" "$etag"
  verify_attachment "$schedule_id" "$function_id"
  printf '%s is attached to %s.\n' "$function_name" "$schedule_name"
}

main "$@"
