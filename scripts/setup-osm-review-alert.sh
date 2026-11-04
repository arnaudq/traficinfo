#!/usr/bin/env bash
# Reconcile a grouped OSM status alarm on an existing confirmed email topic.
set -euo pipefail

source "${TRAFICINFO_ROOT:?Run source ./scripts/setup.sh first}/scripts/oci-common.sh"

current_step=initialization
trap 'report_failure "$?"' EXIT

build_osm_review_alarm() {
  local mapping_status="$1" name title summary guidance count_label
  case "$mapping_status" in
    NEEDS_REVIEW)
      name=TraficInfo-OSMWayMappingsNeedReview
      title='TraficInfo OSM mappings need review'
      summary='OSM mappings require manual review. See the datasource dimensions and metric values.'
      guidance='Inspect OSMWayMapping_NEEDS_REVIEW.json in the OSM bucket. Review candidates and upload any approved assignments for the next hourly OSM refresh.'
      count_label=review
      ;;
    NOT_FOUND)
      name=TraficInfo-OSMWayMappingsNotFound
      title='TraficInfo OSM road ways not found'
      summary='OSM reverse lookups found no suitable road way. See the datasource dimensions and metric values.'
      guidance="Inspect each affected datasource's <datasourceId>_OSMWayMapping.json in the OSM bucket and its stored reverse lookup responses. Check source coordinates and road references, and correct mappings where appropriate."
      count_label=NOT_FOUND
      ;;
    *) log "Unsupported OSM alarm status: $mapping_status"; return 1 ;;
  esac
  jq -cn --arg compartment "$OCI_COMPARTMENT_ID" --arg topic "$topic_id" \
    --arg mapping_status "$mapping_status" --arg name "$name" --arg title "$title" \
    --arg summary "$summary" --arg guidance "$guidance" --arg count_label "$count_label" '{
    compartmentId: $compartment, displayName: $name,
    metricCompartmentId: $compartment, metricCompartmentIdInSubtree: false,
    namespace: "traficinfo",
    queryText: ("OSMWayMappings[1h]{status = \"" + $mapping_status + "\"}.groupBy(datasourceId).max() > 0"),
    severity: "WARNING", isEnabled: true, pendingDuration: "PT1M",
    destinations: [$topic], messageFormat: "ONS_OPTIMIZED",
    isNotificationsPerMetricDimensionEnabled: false, repeatNotificationDuration: "P1D",
    notificationTitle: ($title + " - {{status}}"),
    alarmSummary: $summary,
    body: ($guidance + "\n\nDimensions identify affected datasources; metric values show their " + $count_label + " counts. Values are the maximum within the last hour.\n\nDaily reminders apply while continuously firing. Recovery, missing metrics or a new firing episode can cause additional notifications.\n\nView alarm: {{alarmUrl}}")
  }'
}

osm_review_alarm_matches() {
  jq -e --argjson expected "$2" '
    .data | ."lifecycle-state" == "ACTIVE"
    and ."compartment-id" == $expected.compartmentId
    and ."display-name" == $expected.displayName
    and ."metric-compartment-id" == $expected.metricCompartmentId
    and (."metric-compartment-id-in-subtree" // false) == false
    and .namespace == $expected.namespace and .query == $expected.queryText
    and .severity == $expected.severity and ."is-enabled" == true
    and ."pending-duration" == $expected.pendingDuration
    and .destinations == $expected.destinations and ."message-format" == $expected.messageFormat
    and ."is-notifications-per-metric-dimension-enabled" == false
    and (."repeat-notification-duration" == "P1D" or ."repeat-notification-duration" == "PT24H")
    and ."notification-title" == $expected.notificationTitle
    and ."alarm-summary" == $expected.alarmSummary and .body == $expected.body
    and ((.overrides // []) | length == 0) and .suppression == null
  ' <<< "$1" >/dev/null
}

main() {
  local email dry_run topic_name topic_id topics subscriptions alarms alarm_id payload state name operation=create
  local mapping_status=NEEDS_REVIEW
  local remaining=()
  if [ "$#" -lt 1 ] || [[ ! "$1" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]]; then
    printf 'Usage: %s <email> [--status NEEDS_REVIEW|NOT_FOUND] [--dry-run]\n' "$0" >&2
    return 1
  fi
  email="$1"; shift
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --status)
        if [ "$#" -lt 2 ]; then log '--status requires NEEDS_REVIEW or NOT_FOUND.'; return 1; fi
        mapping_status="$2"; shift 2 ;;
      *) remaining+=("$1"); shift ;;
    esac
  done
  case "$mapping_status" in
    NEEDS_REVIEW|NOT_FOUND) ;;
    *) log "Unsupported OSM alarm status: $mapping_status"; return 1 ;;
  esac
  if [ "${#remaining[@]}" -eq 0 ]; then
    dry_run=false
  else
    dry_run="$(parse_dry_run_arguments "${remaining[@]}")"
  fi
  : "${OCI_COMPARTMENT_ID:?Run source ./scripts/setup.sh first}"
  topic_name="${OCI_FN_ALERT_TOPIC_NAME:-TraficInfoMonitoring}"
  step "Checking topic $topic_name and confirmed email subscription..."
  topics="$(oci ons topic list --compartment-id "$OCI_COMPARTMENT_ID" --name "$topic_name" --all)"
  topic_id="$(jq -c --arg name "$topic_name" '
    [.data[] | select(.name == $name and ."lifecycle-state" == "ACTIVE") | {id: ."topic-id"}]
  ' <<< "$topics" | unique_id)"
  subscriptions="$(oci ons subscription list --compartment-id "$OCI_COMPARTMENT_ID" --topic-id "$topic_id" --all)"
  jq -e --arg email "$email" --arg topic "$topic_id" '
    any(.data[]; ."topic-id" == $topic and .protocol == "EMAIL" and .endpoint == $email
      and ."lifecycle-state" == "ACTIVE")
  ' <<< "$subscriptions" >/dev/null || {
    log "Topic $topic_name needs a confirmed EMAIL subscription for $email."
    return 1
  }
  step "Checking the OSM $mapping_status alarm definition..."
  payload="$(build_osm_review_alarm "$mapping_status")"
  name="$(jq -r .displayName <<< "$payload")"
  alarms="$(oci monitoring alarm list --compartment-id "$OCI_COMPARTMENT_ID" --all)"
  alarm_id="$(jq -c --arg name "$name" '
    [.data[] | select(."display-name" == $name and ."lifecycle-state" != "DELETED")]
  ' <<< "$alarms" | unique_id optional)"
  if [ -n "$alarm_id" ]; then
    state="$(oci monitoring alarm get --alarm-id "$alarm_id")"
    jq -e --arg compartment "$OCI_COMPARTMENT_ID" '
      .data | ."lifecycle-state" == "ACTIVE" and ."compartment-id" == $compartment
        and .suppression == null
    ' <<< "$state" >/dev/null || {
      log 'Existing alarm must be active, in the selected compartment, and have no suppression.'
      return 1
    }
    operation=update
    if osm_review_alarm_matches "$state" "$payload"; then operation=skip; fi
  fi
  if [ "$dry_run" = true ]; then
    jq --arg id "$alarm_id" --arg operation "$operation" '. + {alarmId: $id, operation: $operation}' <<< "$payload"
    log 'Dry run complete; no changes submitted.'
    return 0
  fi
  case "$operation" in
    skip) log "$name already matches."; return ;;
    create)
      state="$(oci monitoring alarm create --from-json "$payload")"
      alarm_id="$(jq -er '.data.id | select(type == "string" and length > 0)' <<< "$state")"
      ;;
    update)
      payload="$(jq '. + {overrides: []}' <<< "$payload")"
      oci monitoring alarm update --alarm-id "$alarm_id" --from-json "$payload" --force >/dev/null
      ;;
  esac
  state="$(oci monitoring alarm get --alarm-id "$alarm_id")"
  osm_review_alarm_matches "$state" "$payload" || { log "$name verification failed."; return 1; }
  log "Verified $name ($alarm_id) via $topic_name."
}

main "$@"
