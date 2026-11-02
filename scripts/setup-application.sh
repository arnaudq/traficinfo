#!/usr/bin/env bash
# Reconcile settings on the existing Functions application; creation is not managed yet.
set -euo pipefail

source "${TRAFICINFO_ROOT:?Run source ./scripts/setup.sh first}/scripts/oci-common.sh"

validate_application() {
  jq -e --arg id "$application_id" --arg compartment "$OCI_COMPARTMENT_ID" '
    .data | .id == $id and ."compartment-id" == $compartment
      and ."lifecycle-state" == "ACTIVE"
  ' <<< "$1" >/dev/null || {
    log 'Application must be active and in the selected compartment.'
    return 1
  }
}

logging_matches() {
  jq -e '.data.logging."line-format" == "JSON"' <<< "$1" >/dev/null
}

wait_for_logging() {
  local state deadline
  deadline=$((SECONDS + 600))
  while :; do
    state="$(oci fn application get --application-id "$application_id")"
    jq -e --arg id "$application_id" --arg compartment "$OCI_COMPARTMENT_ID" '
      .data | .id == $id and ."compartment-id" == $compartment
        and (."lifecycle-state" == "ACTIVE" or ."lifecycle-state" == "UPDATING")
    ' <<< "$state" >/dev/null || {
      log 'Application is no longer active or updating in the selected compartment.'
      return 1
    }
    if logging_matches "$state" && jq -e '.data."lifecycle-state" == "ACTIVE"' <<< "$state" >/dev/null; then
      log 'Verified application JSON log line format.'
      return 0
    fi
    if [ "$SECONDS" -ge "$deadline" ]; then
      log 'Timed out waiting for the application to become active with JSON log lines.'
      return 1
    fi
    sleep 5
  done
}

configure_logging() {
  local state
  step 'Reading application logging settings...'
  state="$(oci fn application get --application-id "$application_id")"
  validate_application "$state"
  if logging_matches "$state"; then
    log 'Application already uses JSON log lines.'
    return 0
  fi
  if [ "$dry_run" = true ]; then
    jq -n --arg id "$application_id" \
      '{operation: "update", applicationId: $id, logging: {lineFormat: "JSON"}}'
    return 0
  fi

  step 'Setting application log line format to JSON...'
  # The CLI's application waiter is not supported by its management client.
  # Submit the update without that waiter, then verify the application itself.
  oci fn application update --application-id "$application_id" \
    --logging '{"lineFormat":"JSON"}' --force >/dev/null
  step 'Verifying application logging settings...'
  wait_for_logging
}

main() {
  local dry_run application_id
  trap 'report_failure "$?"' EXIT
  dry_run="$(parse_dry_run_arguments "$@")"
  : "${OCI_COMPARTMENT_ID:?Run source ./scripts/setup.sh first}"
  : "${OCI_FN_APPLICATION_NAME:?Run source ./scripts/setup.sh first}"
  step "Resolving application $OCI_FN_APPLICATION_NAME..."
  application_id="$(resolve_application_id)"
  configure_logging
}

main "$@"
