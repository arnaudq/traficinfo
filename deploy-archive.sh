#!/usr/bin/env bash
# Build and upload the Java code-only function, without Docker or fn deploy.
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
app_name=3MOpenDataProcessor
function_name=3MOpenDataSource
runtime_name=java21.ol9
handler=org.traficinfo.dsfunctions.m3m.DataSourceFunction::handleRequest
bucket_name=ds3mbucket
jar_file="$root_dir/dsfunctions/target/dsfunctions-1.0-SNAPSHOT-jar-with-dependencies.jar"

resolve_application_id() {
  local application_id compartment_id
  application_id="${OCI_FN_APPLICATION_ID:-}"
  if [ -z "$application_id" ]; then
    compartment_id="${OCI_FN_COMPARTMENT_ID:-}"
    if [ -z "$compartment_id" ]; then
      compartment_id="$(fn inspect context | awk '$1 == "oracle.compartment-id:" {print $2; exit}')"
    fi
    if [ -z "$compartment_id" ]; then
      printf 'Set OCI_FN_APPLICATION_ID or configure oracle.compartment-id in the current Fn context.\n' >&2
      return 1
    fi
    application_id="$(oci fn application list --compartment-id "$compartment_id" \
      --display-name "$app_name" --all --query 'data[0].id' --raw-output)"
  fi
  case "$application_id" in
    ocid1.fnapp.*) printf '%s\n' "$application_id" ;;
    *) printf 'Could not find application %s.\n' "$app_name" >&2; return 1 ;;
  esac
}

# The exact-name lookup leaves the existing 3mopendatasource function alone.
find_function_id() {
  oci fn function list --application-id "$1" --display-name "$function_name" \
    --all --query 'data[0].id' --raw-output
}

build_jar() {
  printf 'Building %s\n' "$jar_file"
  mvn -f "$root_dir/pom.xml" -pl dsfunctions -am -DskipTests package
}

validate_jar() {
  local jar_bytes
  if [ ! -s "$jar_file" ]; then
    printf 'The fat JAR was not produced: %s\n' "$jar_file" >&2
    return 1
  fi
  if ! jar tf "$jar_file" | grep -Fx 'org/traficinfo/dsfunctions/m3m/DataSourceFunction.class' >/dev/null; then
    printf 'The fat JAR does not contain the configured handler class.\n' >&2
    return 1
  fi
  jar_bytes="$(wc -c < "$jar_file" | tr -d '[:space:]')"
  if [ "$jar_bytes" -gt 25000000 ]; then
    printf 'Direct archive upload is limited to 25 MB; this JAR is %s bytes.\n' "$jar_bytes" >&2
    return 1
  fi
}

create_config_json() {
  local namespace
  namespace="$(oci os ns get --query data --raw-output)"
  if [[ ! "$namespace" =~ ^[a-zA-Z0-9_-]+$ ]]; then
    printf 'OCI CLI returned an invalid Object Storage namespace.\n' >&2
    return 1
  fi
  printf '{"NAMESPACE":"%s","BUCKETNAME":"%s"}' "$namespace" "$bucket_name"
}

create_archive_function() {
  local application_id config
  application_id="$1"
  config="$(create_config_json)"
  printf 'Creating archive function %s in %s\n' "$function_name" "$app_name" >&2
  oci fn function create archive-function direct-archive fn-update-runtime-config \
    --application-id "$application_id" --display-name "$function_name" \
    --archive-file "$jar_file" --functions-runtime-name "$runtime_name" \
    --handler "$handler" --memory-in-mbs 2048 --timeout-in-seconds 30 \
    --config "$config" --query '"opc-work-request-id"' --raw-output
}

update_archive_function() {
  local function_id
  function_id="$1"
  printf 'Updating archive function %s (%s)\n' "$function_name" "$function_id" >&2
  oci fn function update archive-function --function-id "$function_id" \
    --archive-file "$jar_file" --handler "$handler" \
    --runtime-config FUNCTION_UPDATE --functions-runtime-name "$runtime_name" \
    --memory-in-mbs 2048 --force \
    --query '"opc-work-request-id"' --raw-output
}

wait_for_work_request() {
  local work_request_id status deadline
  work_request_id="$1"
  if [[ "$work_request_id" != ocid1.functionsworkrequest.* ]]; then
    printf 'OCI did not return a Functions work request OCID.\n' >&2
    return 1
  fi
  deadline=$((SECONDS + 600))

  printf 'Waiting for work request %s\n' "$work_request_id"
  while [ "$SECONDS" -lt "$deadline" ]; do
    status="$(oci fn work-request get --work-request-id "$work_request_id" \
      --query 'data.status' --raw-output)"
    case "$status" in
      SUCCEEDED)
        printf 'Work request %s succeeded.\n' "$work_request_id"
        return 0
        ;;
      FAILED|CANCELED|NEEDS_ATTENTION)
        printf 'Work request %s ended with status %s.\n' "$work_request_id" "$status" >&2
        oci fn work-request-error list --work-request-id "$work_request_id" --all >&2
        return 1
        ;;
    esac
    sleep 5
  done
  printf 'Timed out waiting for work request %s.\n' "$work_request_id" >&2
  return 1
}

main() {
  local application_id function_id work_request_id
  application_id="$(resolve_application_id)"
  function_id="$(find_function_id "$application_id")"
  build_jar
  validate_jar
  if [[ "$function_id" == ocid1.fnfunc.* ]]; then
    work_request_id="$(update_archive_function "$function_id")"
  else
    work_request_id="$(create_archive_function "$application_id")"
  fi
  wait_for_work_request "$work_request_id"
}

main "$@"
