#!/usr/bin/env bash
# Invoke a traffic data-source or GeoJSON function.
set -euo pipefail

usage() {
  printf 'Usage: %s <ds|geojson> <3M|DPT34>\n' "$0" >&2
}

if [ "$#" -ne 2 ]; then
  usage
  exit 1
fi

type="$1"
source="$2"
case "$source" in
  3M|DPT34) ;;
  *) usage; exit 1 ;;
esac
case "$type" in
  ds) function_name="${source}OpenDataSource" ;;
  geojson) function_name="${source}GeoJsonOut" ;;
  *) usage; exit 1 ;;
esac

command -v oci >/dev/null 2>&1 || {
  printf 'OCI CLI is required.\n' >&2
  exit 1
}

app_name=3MOpenDataProcessor
application_id="${OCI_FN_APPLICATION_ID:-}"
if [ -z "$application_id" ]; then
  compartment_id="${OCI_FN_COMPARTMENT_ID:-}"
  if [ -z "$compartment_id" ]; then
    command -v fn >/dev/null 2>&1 || {
      printf 'Set OCI_FN_APPLICATION_ID or OCI_FN_COMPARTMENT_ID, or install and configure the Fn CLI.\n' >&2
      exit 1
    }
    compartment_id="$(fn inspect context | awk '$1 == "oracle.compartment-id:" {print $2; exit}')"
  fi
  if [ -z "$compartment_id" ]; then
    printf 'Set OCI_FN_APPLICATION_ID or configure oracle.compartment-id in the current Fn context.\n' >&2
    exit 1
  fi
  application_id="$(oci fn application list --compartment-id "$compartment_id" \
    --display-name "$app_name" --all --query 'data[0].id' --raw-output)"
fi
case "$application_id" in
  ocid1.fnapp.*) ;;
  *) printf 'Could not find application %s.\n' "$app_name" >&2; exit 1 ;;
esac

function_id="$(oci fn function list --application-id "$application_id" \
  --display-name "$function_name" --all --query 'data[0].id' --raw-output)"
case "$function_id" in
  ocid1.fnfunc.*) ;;
  *)
    printf 'Function %s was not found in %s. Deploy it before invoking.\n' "$function_name" "$app_name" >&2
    exit 1
    ;;
esac

printf 'Invoking %s...\n' "$function_name" >&2
exec oci fn function invoke --function-id "$function_id" \
  --fn-invoke-type sync --body "" --file "-"
