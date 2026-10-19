#!/usr/bin/env bash
# Reconcile one CSV -> GeoJSON OCI Events rule per configured data source.
set -euo pipefail

source "${TRAFICINFO_ROOT:?Run source ./setup.sh first}/oci-common.sh"

application_name="${OCI_FN_APPLICATION_NAME:?Run source ./setup.sh first}"
wait_options=(--wait-for-state ACTIVE --wait-for-state FAILED
  --max-wait-seconds 600 --wait-interval-seconds 5)

current_step=initialization

trap 'report_failure "$?"' EXIT

validate_bucket() {
  local bucket
  bucket="$(oci os bucket get --namespace-name "$2" --name "$bucket_name")"
  jq -e --arg compartment "$1" --arg name "$bucket_name" '
    .data | .name == $name and ."compartment-id" == $compartment
    and ."object-events-enabled" == true
  ' <<< "$bucket" >/dev/null || {
    printf '%s must be in the selected compartment with events enabled. Run ./setup-buckets.sh first.\n' "$bucket_name" >&2
    return 1
  }
}

build_condition() {
  # Both event types are needed: the producer renames old objects before PUT.
  # Exact names exclude archives, companion JSON, and generated GeoJSON objects.
  jq -cn --arg compartment "$1" --arg namespace "$2" \
    --arg bucket "$bucket_name" --arg object "$object_name" '{
      eventType: ["com.oraclecloud.objectstorage.createobject", "com.oraclecloud.objectstorage.updateobject"],
      data: {compartmentId: [$compartment], resourceName: [$object],
        additionalDetails: {namespace: [$namespace], bucketName: [$bucket]}}
    }'
}

build_actions() {
  jq -cn --arg function "$1" '{
    actions: [{actionType: "FAAS", isEnabled: true, functionId: $function}]
  }'
}

find_rule_id() {
  local rules
  # OCI CLI can emit no stdout for an empty list. Capture the successful
  # response first so request failures are never mistaken for an absent rule.
  rules="$(oci events rule list --compartment-id "$1" \
    --display-name "$rule_name" --all)" || return "$?"
  if [[ "$rules" =~ ^[[:space:]]*$ ]]; then
    log "No existing rule named $rule_name; a new rule will be created."
    printf '\n'
    return 0
  fi
  jq -er --arg name "$rule_name" '
      if (.data | type) != "array" then
        error("Expected an Events rule list with a data array") else . end
      |
      [.data[] | select(."display-name" == $name and ."lifecycle-state" != "DELETED")]
      | if length == 0 then "" elif length == 1 then .[0].id else
        error("Multiple rules have the dedicated rule name") end
    ' <<< "$rules"
}

get_rule() {
  oci events rule get --rule-id "$1"
}

validate_existing_rule() {
  jq -e --arg compartment "$2" --arg name "$rule_name" '
    .data | ."compartment-id" == $compartment and ."display-name" == $name
    and (."lifecycle-state" == "ACTIVE" or ."lifecycle-state" == "INACTIVE")
  ' <<< "$1" >/dev/null || {
    printf 'Existing rule is in the wrong compartment or cannot currently be updated.\n' >&2
    return 1
  }
}

rule_matches() {
  jq -e --arg name "$rule_name" --arg compartment "$2" \
    --argjson condition "$3" --arg function "$4" '
    .data | ."display-name" == $name and ."compartment-id" == $compartment
    and ."lifecycle-state" == "ACTIVE" and ."is-enabled" == true
    and (.condition | fromjson) == $condition
    and (.actions.actions | length == 1 and all(.[];
      ."action-type" == "FAAS" and ."is-enabled" == true and ."function-id" == $function))
  ' <<< "$1" >/dev/null
}

preview_rule() {
  jq -n --arg id "$1" --argjson condition "$2" --argjson actions "$3" \
    '{operation: (if $id == "" then "create" else "update" end), ruleId: $id,
      isEnabled: true, condition: $condition, actions: $actions}'
}

create_rule() {
  local result
  log "Creating $rule_name and waiting for ACTIVE (up to 600 seconds)..."
  result="$(oci events rule create --compartment-id "$1" \
    --display-name "$rule_name" --is-enabled true --condition "$2" \
    --actions "$3" "${wait_options[@]}")"
  jq -er '.data.id | select(type == "string" and length > 0)' <<< "$result"
}

update_rule() {
  log "Updating $rule_name and waiting for ACTIVE (up to 600 seconds)..."
  oci events rule update --rule-id "$1" \
    --is-enabled true --condition "$2" --actions "$3" \
    --force "${wait_options[@]}" >/dev/null
}

verify_rule() {
  local state
  state="$(get_rule "$1")"
  rule_matches "$state" "$2" "$3" "$4" || {
    printf 'Rule %s did not reach the requested configuration.\n' "$1" >&2
    return 1
  }
}

reconcile_source() {
  local function_id condition actions rule_id state
  step "Checking $bucket_name and its object events setting..."
  validate_bucket "$compartment_id" "$namespace"
  step "Resolving function $function_name..."
  function_id="$(resolve_function_id "$application_id" "$function_name")"
  step 'Building the CSV event condition and Functions action...'
  condition="$(build_condition "$compartment_id" "$namespace")"
  actions="$(build_actions "$function_id")"
  step "Looking for existing rule $rule_name..."
  rule_id="$(find_rule_id "$compartment_id")"

  if [ -n "$rule_id" ]; then
    step "Reading and checking existing rule $rule_name..."
    state="$(get_rule "$rule_id")"
    validate_existing_rule "$state" "$compartment_id"
    if rule_matches "$state" "$compartment_id" "$condition" "$function_id"; then
      printf '%s already invokes %s for CSV create/update events.\n' "$rule_name" "$function_name"
      return 0
    fi
  fi

  printf 'Configure %s: %s/%s -> %s (%s).\n' \
    "$rule_name" "$bucket_name" "$object_name" "$function_name" "$function_id"
  if [ "$dry_run" = true ]; then
    step 'Showing the proposed rule configuration...'
    preview_rule "$rule_id" "$condition" "$actions"
    return 0
  fi

  if [ -z "$rule_id" ]; then
    step "Creating rule $rule_name..."
    rule_id="$(create_rule "$compartment_id" "$condition" "$actions")"
  else
    step "Updating rule $rule_name..."
    update_rule "$rule_id" "$condition" "$actions"
  fi
  step 'Verifying the active rule, event condition, and function action...'
  verify_rule "$rule_id" "$compartment_id" "$condition" "$function_id"
  printf 'Verified %s (%s): CSV create/update events invoke %s.\n' \
    "$rule_name" "$rule_id" "$function_name"
}

main() {
  local dry_run compartment_id namespace application_id sources source_count source index
  local rule_name function_name bucket_name object_name data_source_id
  dry_run="$(parse_dry_run_arguments "$@")"
  step 'Reading data sources and naming overrides from dsconfig.json...'
  sources="$(load_data_sources)"
  source_count="$(jq 'length' <<< "$sources")"
  if [ "$dry_run" = true ]; then
    log 'Dry run: OCI resources will be read; no changes will be submitted.'
  fi
  compartment_id="${OCI_COMPARTMENT_ID:?Run source ./setup.sh first}"
  namespace="${OCI_OBJECT_STORAGE_NAMESPACE:?Run source ./setup.sh first}"
  step "Resolving application $application_name..."
  application_id="$(resolve_application_id)"
  for ((index = 0; index < source_count; index++)); do
    source="$(jq -c --argjson index "$index" '.[$index]' <<< "$sources")"
    data_source_id="$(jq -r '.id' <<< "$source")"
    rule_name="$(jq -r '.eventRule' <<< "$source")"
    function_name="$(jq -r '.geoJsonFunction' <<< "$source")"
    bucket_name="$(jq -r '.inputBucket' <<< "$source")"
    object_name="$(jq -r '.csvObject' <<< "$source")"
    step "Configuring events for $data_source_id..."
    reconcile_source
  done
  if [ "$dry_run" = true ]; then
    log 'Dry run complete; no changes submitted.'
  else
    log 'All configured data source event rules are ready.'
  fi
}

main "$@"
