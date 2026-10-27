#!/usr/bin/env bash
# Create or reconcile the Object Storage buckets used by the traffic functions.
set -euo pipefail

source "${TRAFICINFO_ROOT:?Run source ./scripts/setup.sh first}/scripts/oci-common.sh"

bucket_definitions() {
  local sources
  sources="$(load_data_sources)" || return
  jq -ce '
    [ .[] | {name: .inputBucket, objectEventsEnabled: true} ]
    + [{name: "traficinfo", publicAccessType: "ObjectReadWithoutList"},
       {name: "osminfo", publicAccessType: "NoPublicAccess"}]
    | unique | group_by(.name)
    | if any(.[]; length > 1) then error("Conflicting settings for the same bucket")
      else map(.[0]) end
  ' <<< "$sources"
}

bucket_matches() {
  jq -e --argjson desired "$2" '
    .data
    | (if $desired.objectEventsEnabled != null then
         ."object-events-enabled" == $desired.objectEventsEnabled else true end)
      and (if $desired.publicAccessType != null then
         ."public-access-type" == $desired.publicAccessType else true end)
  ' <<< "$1" >/dev/null
}

verify_bucket() {
  local definition="$1" name state
  name="$(jq -r '.name' <<< "$definition")"
  state="$(oci os bucket get --namespace-name "$namespace" --name "$name")"
  jq -e --arg compartment "$compartment_id" --arg name "$name" '
    .data | ."compartment-id" == $compartment and .name == $name
  ' <<< "$state" >/dev/null && bucket_matches "$state" "$definition" || {
    log "Bucket $name did not reach the requested configuration."
    return 1
  }
}

reconcile_bucket() {
  local definition="$1" state="$2" name desired
  local options=()
  name="$(jq -r '.name' <<< "$definition")"
  if jq -e '.objectEventsEnabled == true' <<< "$definition" >/dev/null; then
    options=(--object-events-enabled true)
    desired='object events enabled'
  else
    options=(--public-access-type "$(jq -r '.publicAccessType' <<< "$definition")")
    desired="$(jq -r '.publicAccessType' <<< "$definition")"
  fi
  if [ -n "$state" ] && bucket_matches "$state" "$definition"; then
    log "$name already has $desired."
    return 0
  fi
  if [ -z "$state" ]; then
    log "Create $name in $compartment_id: $desired."
    if [ "$dry_run" = false ]; then
      oci os bucket create --namespace-name "$namespace" --name "$name" \
        --compartment-id "$compartment_id" --storage-tier Standard \
        "${options[@]}" >/dev/null
    fi
  else
    log "Update $name: $desired."
    if [ "$dry_run" = false ]; then
      oci os bucket update --namespace-name "$namespace" --name "$name" \
        "${options[@]}" --force >/dev/null
    fi
  fi
  if [ "$dry_run" = false ]; then
    verify_bucket "$definition"
    log "$name configuration verified."
  fi
}

main() {
  local dry_run compartment_id namespace definitions count index definition name matches state buckets
  local states=()
  dry_run="$(parse_dry_run_arguments "$@")"
  compartment_id="${OCI_COMPARTMENT_ID:?Run source ./scripts/setup.sh first}"
  namespace="${OCI_OBJECT_STORAGE_NAMESPACE:?Run source ./scripts/setup.sh first}"
  definitions="$(bucket_definitions)"
  count="$(jq 'length' <<< "$definitions")"
  # List successfully before deciding a bucket is absent.
  buckets="$(oci os bucket list --compartment-id "$compartment_id" \
    --namespace-name "$namespace" --all)"
  if [[ "$buckets" =~ ^[[:space:]]*$ ]]; then buckets='{"data":[]}'; fi
  jq -e '.data | type == "array"' <<< "$buckets" >/dev/null
  # Read and validate all existing buckets before submitting changes.
  for ((index = 0; index < count; index++)); do
    definition="$(jq -c --argjson index "$index" '.[$index]' <<< "$definitions")"
    name="$(jq -r '.name' <<< "$definition")"
    validate_bucket_name "$name"
    matches="$(jq -r --arg name "$name" '[.data[] | select(.name == $name)] | length' <<< "$buckets")"
    state=''
    case "$matches" in
      0) ;;
      1)
        state="$(oci os bucket get --namespace-name "$namespace" --name "$name")"
        jq -e --arg compartment "$compartment_id" --arg name "$name" '
          .data | ."compartment-id" == $compartment and .name == $name
            and ."storage-tier" == "Standard"
        ' <<< "$state" >/dev/null || {
          log "Bucket $name must belong to the selected compartment and use Standard storage."
          return 1
        } ;;
      *) log "Ambiguous bucket name: $name."; return 1 ;;
    esac
    states+=("$state")
  done
  for ((index = 0; index < count; index++)); do
    definition="$(jq -c --argjson index "$index" '.[$index]' <<< "$definitions")"
    reconcile_bucket "$definition" "${states[$index]}"
  done
  if [ "$dry_run" = true ]; then log 'Dry run complete; no changes submitted.'; fi
}

main "$@"
