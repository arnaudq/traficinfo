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
  local query
  # Keep the readable name while combining error codes/invocation types per function.
  query="FunctionResponseCount[1m]{applicationId = \"$application_id\", responseType = \"Error\"}.groupBy(resourceId, resourceDisplayName).sum() > 0"
  jq -cn --arg compartment "$compartment_id" --arg name "$alarm_name" \
    --arg query "$query" --arg topic "$topic_id" '{
      compartmentId: $compartment, displayName: $name,
      metricCompartmentId: $compartment, metricCompartmentIdInSubtree: false,
      namespace: "oci_faas", queryText: $query, severity: "CRITICAL", isEnabled: true,
      pendingDuration: "PT1M", destinations: [$topic], messageFormat: "ONS_OPTIMIZED",
      isNotificationsPerMetricDimensionEnabled: true,
      notificationTitle: "TraficInfo function alert - {{status}}",
      alarmSummary: "Function {{dimensions.resourceDisplayName}}: {{status}}.",
      body: "Function: {{dimensions.resourceDisplayName}}\nState: {{status}}\nTime: {{timestamp}}\n\nFIRING means a function error was detected. Check this function\u0027s invocation logs to investigate. OK means the error condition has cleared; verify a successful run to confirm recovery.\n\nView alarm: {{alarmUrl}}\nFunction ID: {{dimensions.resourceId}}"
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

main() {
  local email dry_run compartment_id application_id topic_name topic_id topics subscriptions
  local alarm_name alarms alarm_id state payload result
  if [ "$#" -lt 1 ] || [[ ! "$1" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]]; then
    printf 'Usage: %s <email> [--dry-run]\n' "$0" >&2
    return 1
  fi
  email="$1"; shift
  dry_run="$(parse_dry_run_arguments "$@")"
  compartment_id="${OCI_COMPARTMENT_ID:?Run source ./scripts/setup.sh first}"
  topic_name="${OCI_FN_ALERT_TOPIC_NAME:-TraficInfoMonitoring}"
  alarm_name="${OCI_FN_APPLICATION_NAME:?Run source ./scripts/setup.sh first}-FunctionErrors"
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
  step "Checking alarm $alarm_name..."
  alarms="$(read_resources monitoring alarm list --compartment-id "$compartment_id" --display-name "$alarm_name" --all)"
  alarm_id="$(jq -c --arg name "$alarm_name" '
    [.[] | select(."display-name" == $name and ."lifecycle-state" != "DELETED")]
  ' <<< "$alarms" | unique_id optional)"
  payload="$(build_alarm)"
  if [ -n "$alarm_id" ]; then
    state="$(oci monitoring alarm get --alarm-id "$alarm_id")"
    jq -e '.data.suppression == null' <<< "$state" >/dev/null || {
      log 'Remove the existing alarm suppression in OCI before reconciling failure alerts.'
      return 1
    }
    if alarm_matches "$state" "$payload"; then
      log "Function failure alerts already enabled for $email via $topic_name."
      return 0
    fi
  fi
  if [ "$dry_run" = true ]; then
    jq --arg id "$alarm_id" '. + {alarmId: $id, operation:
      (if $id == "" then "create" else "update" end)}' <<< "$payload"
    log 'Dry run complete; no changes submitted.'
    return 0
  fi
  step 'Enabling function failure alerts...'
  if [ -z "$alarm_id" ]; then
    result="$(oci monitoring alarm create --from-json "$payload")"
    alarm_id="$(jq -er '.data.id | select(type == "string" and length > 0)' <<< "$result")"
  else
    # Remove old overrides so they cannot hide a failure.
    payload="$(jq '. + {overrides: []}' <<< "$payload")"
    oci monitoring alarm update --alarm-id "$alarm_id" --from-json "$payload" --force >/dev/null
  fi
  state="$(oci monitoring alarm get --alarm-id "$alarm_id")"
  alarm_matches "$state" "$payload" || { log 'Alarm verification failed.'; return 1; }
  log "Verified function failure alerts for $email via $topic_name ($alarm_id)."
}

main "$@"
