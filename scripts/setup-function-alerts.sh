#!/usr/bin/env bash
# Send function error alarms to an existing, confirmed email notification topic.
set -euo pipefail

source "${TRAFICINFO_ROOT:?Run source ./scripts/setup.sh first}/scripts/oci-common.sh"

current_step=initialization
trap 'report_failure "$?"' EXIT

read_resources() {
  local result
  result="$(oci "$@")" || return
  if [[ "$result" =~ ^[[:space:]]*$ ]]; then result='{"data":[]}'; fi
  jq -ce 'if (.data | type) == "array" then .data else error("Expected a resource array") end' <<< "$result"
}

build_alarm() {
  local kind="$1" name="$2" query interval filter threshold severity title guidance
  case "$kind" in
    errors)
      interval=1m; filter='ErrorCode != "503"'; threshold='> 0'; severity=CRITICAL
      title='TraficInfo function error'
      guidance='A non-503 function error was detected. Check the error details and invocation logs.'
      ;;
    availability)
      interval=15m; filter='ErrorCode = "503"'; threshold='>= 3'; severity=WARNING
      title='TraficInfo function availability'
      guidance='At least three 503 responses were recorded within 15 minutes. OCI could not serve these invocation attempts. Check for a subsequent successful run. This threshold does not mean retries are exhausted.'
      ;;
    *) log "Unknown alarm kind: $kind"; return 1 ;;
  esac
  query="FunctionResponseCount[$interval]{applicationId = \"$application_id\", responseType = \"Error\", $filter}.groupBy(resourceId, resourceDisplayName, ErrorCode, ErrorMessage).sum() $threshold"
  jq -cn --arg compartment "$compartment_id" --arg name "$name" \
    --arg query "$query" --arg topic "$topic_id" --arg severity "$severity" \
    --arg title "$title" --arg guidance "$guidance" '{
      compartmentId: $compartment, displayName: $name,
      metricCompartmentId: $compartment, metricCompartmentIdInSubtree: false,
      namespace: "oci_faas", queryText: $query, severity: $severity, isEnabled: true,
      pendingDuration: "PT1M", destinations: [$topic], messageFormat: "ONS_OPTIMIZED",
      isNotificationsPerMetricDimensionEnabled: true,
      notificationTitle: ($title + " - {{status}}"),
      alarmSummary: "Function {{dimensions.resourceDisplayName}}: {{status}}. Error {{dimensions.ErrorCode}} - {{dimensions.ErrorMessage}}.",
      body: ("Function: {{dimensions.resourceDisplayName}}\nState: {{status}}\nTime: {{timestamp}}\nError: {{dimensions.ErrorCode}} - {{dimensions.ErrorMessage}}\n\nWhen FIRING: " + $guidance + "\n\nOK means the error condition has cleared; verify a successful run to confirm recovery.\n\nView alarm: {{alarmUrl}}\nFunction ID: {{dimensions.resourceId}}")
    }'
}

alarm_matches() {
  jq -e --argjson expected "$2" '
    .data | ."lifecycle-state" == "ACTIVE"
    and ."compartment-id" == $expected.compartmentId
    and ."display-name" == $expected.displayName
    and ."metric-compartment-id" == $expected.metricCompartmentId
    and (."metric-compartment-id-in-subtree" // false) == false
    and .namespace == $expected.namespace and ."query" == $expected.queryText
    and .severity == $expected.severity and ."is-enabled" == true
    and ."pending-duration" == $expected.pendingDuration
    and .destinations == $expected.destinations and ."message-format" == $expected.messageFormat
    and ."is-notifications-per-metric-dimension-enabled" == true
    and .body == $expected.body
    and ."notification-title" == $expected.notificationTitle
    and ."alarm-summary" == $expected.alarmSummary
    and ((.overrides // []) | length == 0) and .suppression == null
  ' <<< "$1" >/dev/null
}

find_alarm_id() {
  jq -c --arg name "$1" '
    [.[] | select(."display-name" == $name and ."lifecycle-state" != "DELETED")]
  ' <<< "$alarms" | unique_id optional
}

apply_alarm() {
  local alarm_id="$1" payload="$2" result state
  if [ -z "$alarm_id" ]; then
    result="$(oci monitoring alarm create --from-json "$payload")" || return
    alarm_id="$(jq -er '.data.id | select(type == "string" and length > 0)' <<< "$result")" || return
  else
    # Rename the legacy catch-all alarm when migrating, and remove old overrides.
    payload="$(jq '. + {overrides: []}' <<< "$payload")" || return
    oci monitoring alarm update --alarm-id "$alarm_id" --from-json "$payload" --force >/dev/null || return
  fi
  state="$(oci monitoring alarm get --alarm-id "$alarm_id")" || return
  alarm_matches "$state" "$payload" || { log 'Alarm verification failed.'; return 1; }
  log "Verified $(jq -r '.displayName' <<< "$payload") ($alarm_id)."
}

main() {
  local email dry_run compartment_id application_id topic_name topic_id topics subscriptions
  local alarms alarm_id legacy_id state payload name kind operation index plans='[]'
  # Create availability coverage before narrowing the legacy catch-all to non-503 errors.
  local names=(TraficInfo-FunctionAvailability TraficInfo-FunctionErrors)
  local kinds=(availability errors)
  if [ "$#" -lt 1 ] || [[ ! "$1" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]]; then
    printf 'Usage: %s <email> [--dry-run]\n' "$0" >&2
    return 1
  fi
  email="$1"; shift
  dry_run="$(parse_dry_run_arguments "$@")"
  compartment_id="${OCI_COMPARTMENT_ID:?Run source ./scripts/setup.sh first}"
  topic_name="${OCI_FN_ALERT_TOPIC_NAME:-TraficInfoMonitoring}"
  : "${OCI_FN_APPLICATION_NAME:?Run source ./scripts/setup.sh first}"
  step "Resolving application $OCI_FN_APPLICATION_NAME..."
  application_id="$(resolve_application_id)"
  step "Checking existing topic $topic_name and confirmed email subscription..."
  topics="$(read_resources ons topic list --compartment-id "$compartment_id" --name "$topic_name" --all)"
  topic_id="$(jq -c --arg name "$topic_name" '
    [.[] | select(.name == $name and ."lifecycle-state" == "ACTIVE") | {id: ."topic-id"}]
  ' <<< "$topics" | unique_id)"
  subscriptions="$(read_resources ons subscription list --compartment-id "$compartment_id" --topic-id "$topic_id" --all)"
  jq -e --arg email "$email" --arg topic "$topic_id" '
    any(.[]; ."topic-id" == $topic and .protocol == "EMAIL" and .endpoint == $email
      and ."lifecycle-state" == "ACTIVE")
  ' <<< "$subscriptions" >/dev/null || {
    log "Topic $topic_name needs a confirmed EMAIL subscription for $email before alerts can be enabled."
    return 1
  }
  step 'Checking both alarm definitions before submitting changes...'
  alarms="$(read_resources monitoring alarm list --compartment-id "$compartment_id" --all)"
  legacy_id="$(find_alarm_id "$OCI_FN_APPLICATION_NAME-FunctionErrors")"
  for index in "${!names[@]}"; do
    name="${names[$index]}"; kind="${kinds[$index]}"
    alarm_id="$(find_alarm_id "$name")"
    if [ "$kind" = errors ] && [ -n "$legacy_id" ]; then
      if [ -n "$alarm_id" ]; then
        log 'Both legacy and replacement error alarms exist; resolve the duplicate coverage before setup.'
        return 1
      fi
      alarm_id="$legacy_id"
    fi
    payload="$(build_alarm "$kind" "$name")"
    operation=create
    if [ -n "$alarm_id" ]; then
      state="$(oci monitoring alarm get --alarm-id "$alarm_id")"
      jq -e --arg compartment "$compartment_id" '
        .data | ."lifecycle-state" == "ACTIVE" and ."compartment-id" == $compartment
          and .suppression == null
      ' <<< "$state" >/dev/null || {
        log 'Existing alarms must be active, in the selected compartment, and have no suppression.'
        return 1
      }
      operation=update
      if alarm_matches "$state" "$payload"; then operation=skip; fi
    fi
    plans="$(jq -c --arg id "$alarm_id" --arg operation "$operation" --argjson payload "$payload" \
      '. + [{alarmId: $id, operation: $operation, payload: $payload}]' <<< "$plans")"
  done
  if [ "$dry_run" = true ]; then
    jq 'map(.payload + {alarmId: .alarmId, operation: .operation})' <<< "$plans"
    log 'Dry run complete; no changes submitted.'
    return 0
  fi
  for index in "${!names[@]}"; do
    operation="$(jq -r --argjson i "$index" '.[$i].operation' <<< "$plans")"
    if [ "$operation" = skip ]; then log "${names[$index]} already matches."; continue; fi
    step "Configuring ${names[$index]}..."
    alarm_id="$(jq -r --argjson i "$index" '.[$i].alarmId' <<< "$plans")"
    payload="$(jq -c --argjson i "$index" '.[$i].payload' <<< "$plans")"
    apply_alarm "$alarm_id" "$payload"
  done
  log "Both function alarms are ready for $email via $topic_name."
}

main "$@"
