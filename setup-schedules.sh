#!/usr/bin/env bash
# Ensure Hourly, Daily, Weekly and Monthly schedules use defined-tag resource filters.
set -euo pipefail

source "${TRAFICINFO_ROOT:?Run source ./setup.sh first}/oci-common.sh"

# Recorded from OCI during script preparation; no schedule is a runtime template.
hourly_clock='BYMINUTE=0;BYSECOND=0'
schedule_clock='BYHOUR=15;BYMINUTE=30;BYSECOND=0'
schedule_timezone=UTC
tag_namespace=TraficInfo
tag_key=DataSourceSchedule
wait_options=(--wait-for-state SUCCEEDED --wait-for-state FAILED --wait-for-state CANCELED
  --max-wait-seconds 600 --wait-interval-seconds 5)

find_schedule_id() {
  # Match case-insensitively: the existing project schedule is named Daily.
  jq -er --arg name "$1" '
    [.data.items[] | select((."display-name" | ascii_downcase) == ($name | ascii_downcase)
      and ."lifecycle-state" != "DELETED")]
    | if length == 0 then "" elif length == 1 then .[0].id else
      error("Multiple schedules match \($name)") end
  ' <<< "$schedules"
}

build_configuration() {
  jq -cn --arg compartment "$compartment_id" --arg name "$1" --arg rule "$2" \
    --arg timezone "$schedule_timezone" --arg namespace "$tag_namespace" --arg key "$tag_key" '{
      compartmentId: $compartment, displayName: $name, action: "START_RESOURCE",
      recurrenceType: "ICAL", recurrenceDetails: $rule, localTimeZone: $timezone,
      resourceFilters: [
        {attribute: "COMPARTMENT_ID", value: $compartment, shouldIncludeChildCompartments: false},
        {attribute: "RESOURCE_TYPE", value: ["FunctionsFunction"]},
        {attribute: "DEFINED_TAGS", value: [{namespace: $namespace, tagKey: $key, value: $name}]}
      ]
    }'
}

validate_existing_schedule() {
  jq -e --arg compartment "$compartment_id" '
    .data | ."compartment-id" == $compartment and ."lifecycle-state" == "ACTIVE"
      and .action == "START_RESOURCE"
  ' <<< "$1" >/dev/null || {
    log 'Existing schedule must be an active START_RESOURCE schedule in the selected compartment.'
    return 1
  }
}

schedule_matches() {
  # Only convert schema field names; tag names and values remain literal.
  jq -e --argjson expected "$2" '
    def normalized_filters:
      map(if .attribute == "COMPARTMENT_ID" then
            {attribute, value, shouldIncludeChildCompartments:
              (.shouldIncludeChildCompartments // ."should-include-child-compartments" // false)}
          elif .attribute == "DEFINED_TAGS" then
            {attribute, value: [.value[] | {namespace, value, tagKey: (.tagKey // ."tag-key")}]}
          else . end) | sort_by(.attribute);
    .data | ."lifecycle-state" == "ACTIVE"
      and ."compartment-id" == $expected.compartmentId
      and .action == $expected.action
      and ."recurrence-type" == $expected.recurrenceType
      and ."recurrence-details" == $expected.recurrenceDetails
      and (."local-time-zone" // "UTC") == $expected.localTimeZone
      and ((.resources // []) | length == 0)
      and ((."resource-filters" // [] | normalized_filters)
        == ($expected.resourceFilters | normalized_filters))
  ' <<< "$1" >/dev/null
}

check_work_request() {
  jq -e '.data.status == "SUCCEEDED"' <<< "$1" >/dev/null || {
    log "Schedule operation did not succeed: $1"
    return 1
  }
}

verify_schedule() {
  local state
  state="$(oci resource-scheduler schedule get --schedule-id "$1")"
  schedule_matches "$state" "$2" || { log "Verification failed for $1."; return 1; }
  log "Verified schedule $1."
}

create_schedule() {
  local name="$1" payload="$2" result id
  # OCI permits duplicate names; catch additions since discovery before creating.
  schedules="$(list_schedules)"
  id="$(find_schedule_id "$name")"
  if [ -n "$id" ]; then
    log "$name appeared during this run; rerun to reconcile it."
    return 1
  fi
  log "Creating $name with tag filters and waiting for completion..."
  result="$(oci resource-scheduler schedule create --from-json "$payload" "${wait_options[@]}")"
  check_work_request "$result"
  schedules="$(list_schedules)"
  id="$(find_schedule_id "$name")"
  [ -n "$id" ] || { log "Created $name was not found."; return 1; }
  verify_schedule "$id" "$payload"
}

update_schedule() {
  local id="$1" payload="$2" result filters
  filters="$(jq -c '.resourceFilters' <<< "$payload")"
  log "Updating $id to use tag filters and waiting for completion..."
  # Empty the previous static target list when switching to dynamic selection.
  # Preserve tags, description and start/end dates.
  result="$(oci resource-scheduler schedule update --schedule-id "$id" \
    --resource-filters "$filters" --resources '[]' \
    --action START_RESOURCE --recurrence-type ICAL \
    --recurrence-details "$(jq -r '.recurrenceDetails' <<< "$payload")" \
    --local-time-zone "$schedule_timezone" --force "${wait_options[@]}")"
  check_work_request "$result"
  verify_schedule "$id" "$payload"
}

list_schedules() {
  local result
  # Do not mistake a failed request for an empty list.
  result="$(oci resource-scheduler schedule list --compartment-id "$compartment_id" --all)" || return "$?"
  if [[ "$result" =~ ^[[:space:]]*$ ]]; then
    printf '{"data":{"items":[]}}\n'
  else
    jq -ce 'if (.data.items | type) == "array" then . else error("Invalid schedule list") end' <<< "$result"
  fi
}

main() {
  local dry_run=false compartment_id schedules
  local name id recurrence payload index state
  local names=(Hourly Daily Weekly Monthly) ids=() payloads=() operations=()
  dry_run="$(parse_dry_run_arguments "$@")"
  compartment_id="${OCI_COMPARTMENT_ID:?Run source ./setup.sh first}"
  log 'Reading existing schedules...'
  schedules="$(list_schedules)"
  # Check all names for ambiguity before submitting any creation.
  for name in "${names[@]}"; do ids+=("$(find_schedule_id "$name")"); done
  log "Using hourly timing $hourly_clock; other schedules $schedule_clock; timezone $schedule_timezone."
  for index in "${!names[@]}"; do
    name="${names[$index]}"
    case "$name" in
      Hourly) recurrence="FREQ=HOURLY;$hourly_clock" ;;
      Daily) recurrence="FREQ=DAILY;$schedule_clock" ;;
      Weekly) recurrence="FREQ=WEEKLY;BYDAY=WE;$schedule_clock" ;;
      Monthly) recurrence="FREQ=MONTHLY;BYMONTHDAY=5;$schedule_clock" ;;
    esac
    payload="$(build_configuration "$name" "$recurrence")"
    payloads+=("$payload")
    if [ -n "${ids[$index]}" ]; then
      state="$(oci resource-scheduler schedule get --schedule-id "${ids[$index]}")"
      validate_existing_schedule "$state"
      if schedule_matches "$state" "$payload"; then
        operations+=(skip)
        log "$name already uses the requested timing and tag filters."
      else
        operations+=(update)
        log "$name will be updated to use $tag_namespace.$tag_key=$name; $recurrence ($schedule_timezone)."
      fi
    else
      operations+=(create)
      log "Missing $name: $recurrence; $tag_namespace.$tag_key=$name"
    fi
    if [ "$dry_run" = true ] && [ "${operations[$index]}" != skip ]; then
      jq --arg operation "${operations[$index]}" --arg id "${ids[$index]}" \
        '. + {operation: $operation, scheduleId: $id, resources: []}' <<< "$payload"
    fi
  done
  if [ "$dry_run" = true ]; then log 'Dry run complete; no changes submitted.'; return 0; fi
  for index in "${!names[@]}"; do
    case "${operations[$index]}" in
      create) create_schedule "${names[$index]}" "${payloads[$index]}" ;;
      update) update_schedule "${ids[$index]}" "${payloads[$index]}" ;;
      skip) ;;
    esac
  done
  log 'Hourly, Daily, Weekly and Monthly schedules use the configured tag filters.'
}

main "$@"
