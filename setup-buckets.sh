#!/usr/bin/env bash
# Create or reconcile the Object Storage buckets used by the 3M functions.
set -euo pipefail

dry_run=false
case "${1:-}" in
  '') ;;
  --dry-run) dry_run=true ;;
  *) printf 'Usage: %s [--dry-run]\n' "$0" >&2; exit 1 ;;
esac
if [ "$#" -gt 1 ]; then
  printf 'Usage: %s [--dry-run]\n' "$0" >&2
  exit 1
fi

for tool in oci jq; do
  command -v "$tool" >/dev/null 2>&1 || {
    printf '%s is required.\n' "$tool" >&2
    exit 1
  }
done
export OCI_CLI_CONFIG_FILE="${OCI_CLI_CONFIG_FILE:-$HOME/.oci/config.arnaudq}"

compartment_id="${OCI_BUCKET_COMPARTMENT_ID:-}"
if [ -z "$compartment_id" ]; then
  compartment_id="$(oci iam compartment list --compartment-id-in-subtree true \
    --name TraficInfo --lifecycle-state ACTIVE --all | jq -er '
      .data | if length == 1 then .[0].id else
        error("Expected exactly one active TraficInfo compartment") end')"
fi
case "$compartment_id" in
  ocid1.compartment.*) ;;
  *) printf 'Invalid OCI_BUCKET_COMPARTMENT_ID.\n' >&2; exit 1 ;;
esac
namespace="$(oci os ns get --query data --raw-output)"
if [ -z "$namespace" ] || [ "$namespace" = null ]; then
  printf 'OCI did not return an Object Storage namespace.\n' >&2
  exit 1
fi

# List successfully before deciding a bucket is absent. Authentication or network
# errors must not be interpreted as a missing bucket.
buckets="$(oci os bucket list --compartment-id "$compartment_id" \
  --namespace-name "$namespace" --all)"
names=(ds3mbucket traficinfo osminfo)
states=()
for name in "${names[@]}"; do
  case "$name" in
    ds3mbucket|traficinfo|osminfo) ;;
    *) printf 'Unsupported bucket name: %s.\n' "$name" >&2; exit 1 ;;
  esac
  count="$(jq -er --arg name "$name" '[.data[] | select(.name == $name)] | length' <<< "$buckets")"
  case "$count" in
    0) states+=('') ;;
    1)
      state="$(oci os bucket get --namespace-name "$namespace" --name "$name")"
      jq -e --arg compartment "$compartment_id" --arg name "$name" '
        .data | ."compartment-id" == $compartment and .name == $name
        and ."storage-tier" == "Standard"
      ' <<< "$state" >/dev/null || {
        printf 'Bucket %s must belong to the selected compartment and use Standard storage.\n' "$name" >&2
        exit 1
      }
      states+=("$state") ;;
    *) printf 'Ambiguous bucket name: %s.\n' "$name" >&2; exit 1 ;;
  esac
done

verify_bucket() {
  local state
  state="$(oci os bucket get --namespace-name "$namespace" --name "$1")"
  jq -e --arg compartment "$compartment_id" --arg name "$1" '
    .data | ."compartment-id" == $compartment and .name == $name
    and (if $name == "ds3mbucket" then ."object-events-enabled" == true
         elif $name == "traficinfo" then ."public-access-type" == "ObjectReadWithoutList"
         elif $name == "osminfo" then ."public-access-type" == "NoPublicAccess"
         else false end)
  ' <<< "$state" >/dev/null || {
    printf 'Bucket %s did not reach the requested configuration.\n' "$1" >&2
    return 1
  }
}

for index in "${!names[@]}"; do
  name="${names[$index]}"
  state="${states[$index]}"
  options=()
  if [ "$name" = ds3mbucket ]; then
    options=(--object-events-enabled true)
    desired='object events enabled'
    satisfied='.data."object-events-enabled" == true'
  elif [ "$name" = traficinfo ]; then
    options=(--public-access-type ObjectReadWithoutList)
    desired='public object reads enabled (without listing)'
    satisfied='.data."public-access-type" == "ObjectReadWithoutList"'
  elif [ "$name" = osminfo ]; then
    options=(--public-access-type NoPublicAccess)
    desired='private access enabled'
    satisfied='.data."public-access-type" == "NoPublicAccess"'
  else
    printf 'Unsupported bucket name: %s.\n' "$name" >&2
    exit 1
  fi

  if [ -n "$state" ] && jq -e "$satisfied" <<< "$state" >/dev/null; then
    printf '%s already has %s.\n' "$name" "$desired"
    continue
  fi
  if [ -z "$state" ]; then
    printf 'Create %s in %s: %s.\n' "$name" "$compartment_id" "$desired"
    if [ "$dry_run" = false ]; then
      oci os bucket create --namespace-name "$namespace" --name "$name" \
        --compartment-id "$compartment_id" --storage-tier Standard \
        "${options[@]}" >/dev/null
    fi
  else
    etag="$(jq -er '.etag | select(type == "string" and length > 0)' <<< "$state")"
    printf 'Update %s: %s.\n' "$name" "$desired"
    if [ "$dry_run" = false ]; then
      # Update only the requested property; reject concurrent bucket edits.
      oci os bucket update --namespace-name "$namespace" --name "$name" \
        --if-match "$etag" "${options[@]}" --force >/dev/null
    fi
  fi
  if [ "$dry_run" = false ]; then
    verify_bucket "$name"
    printf '%s configuration verified.\n' "$name"
  fi
done
if [ "$dry_run" = true ]; then
  printf 'Dry run complete; no changes submitted.\n'
fi
