#!/usr/bin/env bash
# Reconcile per-datasource retrieval schedules and the existing hourly OSM schedule.
set -euo pipefail

source "${TRAFICINFO_ROOT:?Run source ./scripts/setup.sh first}/scripts/oci-common.sh"

wait_options=(--wait-for-state SUCCEEDED --wait-for-state FAILED --wait-for-state CANCELED
  --max-wait-seconds 600 --wait-interval-seconds 5)

find_schedule_id() {
  # OCI permits duplicate names; require a unique case-insensitive match.
  jq -er --arg name "$1" '
    [.data.items[] | select((."display-name" | ascii_downcase) == ($name | ascii_downcase)
      and ."lifecycle-state" != "DELETED")]
    | if length == 0 then "" elif length == 1 then .[0].id else
      error("Multiple schedules match \($name)") end
  ' <<< "$schedules"
}

build_configurations() {
  jq -ce --arg compartment "$compartment_id" --arg function "$retrieval_function_id" \
    '
    def configuration($name; $rule; $resource):
      {compartmentId: $compartment, displayName: $name, action: "START_RESOURCE",
       recurrenceType: "ICAL", recurrenceDetails: $rule, localTimeZone: "UTC",
       resources: [$resource], resourceFilters: []};
    [(configuration("Hourly"; "FREQ=HOURLY;BYMINUTE=0;BYSECOND=0"; {})
      | .resources = [] | .resourceFilters = [
        {attribute: "COMPARTMENT_ID", value: $compartment, shouldIncludeChildCompartments: false},
        {attribute: "RESOURCE_TYPE", value: ["FunctionsFunction"]},
        {attribute: "DEFINED_TAGS", value: [{namespace: "TraficInfo", tagKey: "DataSourceSchedule", value: "Hourly"}]}
      ])]
    + map(
      .schedule as $cadence
      | if .scheduleClock != null and (.scheduleClock | type) != "string" then
          error("scheduleClock must be a string for \(.id)") else . end
      | (.scheduleClock // (if $cadence == "Hourly" then "BYMINUTE=0;BYSECOND=0"
         else "BYHOUR=15;BYMINUTE=30;BYSECOND=0" end)) as $clock
      | if ($clock | type) != "string" then error("scheduleClock must be a string for \(.id)")
        elif $cadence == "Hourly" then
          if ($clock | test("^BYMINUTE=([0-9]|[0-5][0-9]);BYSECOND=0$")) then .
          else error("Invalid hourly scheduleClock for \(.id)") end
        elif (["Daily", "Weekly", "Monthly"] | index($cadence)) != null then
          if ($clock | test("^BYHOUR=([0-9]|[01][0-9]|2[0-3]);BYMINUTE=([0-9]|[0-5][0-9]);BYSECOND=0$")) then .
          else error("Invalid scheduleClock for \(.id)") end
        else error("Invalid schedule for \(.id)") end
      | ((if $cadence == "Hourly" then "FREQ=HOURLY;"
         elif $cadence == "Daily" then "FREQ=DAILY;"
         elif $cadence == "Weekly" then "FREQ=WEEKLY;BYDAY=WE;"
         else "FREQ=MONTHLY;BYMONTHDAY=5;" end) + $clock) as $rule
      | configuration(.id + "Retrieval"; $rule;
          {id: $function, parameters: [{parameterType: "BODY", value: {datasourceId: .id}}]})
    )
  ' <<< "$sources"
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
  jq -e --argjson expected "$2" '
    def filters:
      map(if .attribute == "COMPARTMENT_ID" then
        {attribute, value, shouldIncludeChildCompartments:
          (.shouldIncludeChildCompartments // ."should-include-child-compartments" // false)}
      elif .attribute == "DEFINED_TAGS" then
        {attribute, value: [.value[] | {namespace, value, tagKey: (.tagKey // ."tag-key")}]}
      else . end) | sort_by(.attribute);
    def resources:
      map({id, parameters: ((.parameters // []) | map(
        {parameterType: (.parameterType // ."parameter-type"),
         value: (.value | . as $value |
           if type == "string" then try fromjson catch $value else . end)}))});
    .data | ."lifecycle-state" == "ACTIVE"
      and ."compartment-id" == $expected.compartmentId
      and .action == $expected.action
      and ."recurrence-type" == $expected.recurrenceType
      and ."recurrence-details" == $expected.recurrenceDetails
      and (."local-time-zone" // "UTC") == $expected.localTimeZone
      and ((."resource-filters" // [] | filters) == ($expected.resourceFilters | filters))
      and ((.resources // [] | resources) == ($expected.resources | resources))
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
  log "Creating $name with invocation payload and waiting for completion..."
  result="$(oci resource-scheduler schedule create --from-json "$payload" "${wait_options[@]}")"
  check_work_request "$result"
  schedules="$(list_schedules)"
  id="$(find_schedule_id "$name")"
  [ -n "$id" ] || { log "Created $name was not found."; return 1; }
  verify_schedule "$id" "$payload"
}

update_schedule() {
  local id="$1" payload="$2" result
  log "Updating $id and waiting for completion..."
  result="$(oci resource-scheduler schedule update --schedule-id "$id" \
    --resource-filters "$(jq -c '.resourceFilters' <<< "$payload")" --resources "$(jq -c '.resources' <<< "$payload")" \
    --action START_RESOURCE --recurrence-type ICAL \
    --recurrence-details "$(jq -r '.recurrenceDetails' <<< "$payload")" \
    --local-time-zone UTC --force "${wait_options[@]}")"
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
  local dry_run compartment_id schedules sources configurations
  local name id payload index state retrieval_function_id application_id
  local names=() ids=() payloads=() operations=()
  dry_run="$(parse_dry_run_arguments "$@")"
  compartment_id="${OCI_COMPARTMENT_ID:?Run source ./scripts/setup.sh first}"
  sources="$(load_data_sources)"
  # Validate all clocks before OCI discovery; placeholders are replaced after lookup.
  retrieval_function_id=VALIDATION
  build_configurations >/dev/null
  application_id="$(resolve_application_id)"
  retrieval_function_id="$(resolve_function_id "$application_id" DataSourceFunction)"
  configurations="$(build_configurations)"
  schedules="$(list_schedules)"
  while IFS= read -r payload; do
    name="$(jq -r '.displayName' <<< "$payload")"
    id="$(find_schedule_id "$name")"
    names+=("$name"); ids+=("$id"); payloads+=("$payload")
    if [ -n "$id" ]; then
      state="$(oci resource-scheduler schedule get --schedule-id "$id")"
      validate_existing_schedule "$state"
      if schedule_matches "$state" "$payload"; then operations+=(skip)
      else operations+=(update); fi
    else
      operations+=(create)
    fi
  done < <(jq -c '.[]' <<< "$configurations")
  for index in "${!names[@]}"; do
    log "${operations[$index]} ${names[$index]}: $(jq -r '.recurrenceDetails' <<< "${payloads[$index]}") (UTC)"
    if [ "$dry_run" = true ] && [ "${operations[$index]}" != skip ]; then
      jq --arg operation "${operations[$index]}" --arg id "${ids[$index]}" \
        '. + {operation: $operation, scheduleId: $id}' <<< "${payloads[$index]}"
    fi
  done
  if [ "$dry_run" = true ]; then
    log 'Dry run complete; no changes submitted.'
    return 0
  fi
  for index in "${!names[@]}"; do
    case "${operations[$index]}" in
      create) create_schedule "${names[$index]}" "${payloads[$index]}" ;;
      update) update_schedule "${ids[$index]}" "${payloads[$index]}" ;;
      skip) ;;
    esac
  done
  log 'Per-datasource retrieval schedules and the hourly OSM schedule are ready.'
}

main "$@"
