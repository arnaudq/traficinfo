#!/usr/bin/env bash
# Source this file to initialize the shared project and OCI environment:
#   source ./setup.sh

set_project_paths() {
  local script_file project_root
  if [ -n "${ZSH_VERSION:-}" ]; then
    script_file="${(%):-%x}"
  else
    script_file="${BASH_SOURCE[0]}"
  fi
  project_root="$(cd "$(dirname "$script_file")" && pwd)" || return
  export TRAFICINFO_ROOT="$project_root"
  export TRAFICINFO_DSCONFIG="$project_root/dsconfig.json"
}

set_java_home() {
  local jdk_home
  jdk_home="$(/usr/libexec/java_home -v 25)" || return
  export JAVA_HOME="$jdk_home"
  printf 'JAVA_HOME=%s\n' "$JAVA_HOME"
}

set_oci_config() {
  export OCI_CLI_CONFIG_FILE="${OCI_CLI_CONFIG_FILE:-$HOME/.oci/config.arnaudq}"
  if [ ! -f "$OCI_CLI_CONFIG_FILE" ]; then
    printf 'OCI config file not found: %s\n' "$OCI_CLI_CONFIG_FILE" >&2
    return 1
  fi

  printf 'OCI_CLI_CONFIG_FILE=%s\n' "$OCI_CLI_CONFIG_FILE"
}

set_fn_oracle_provider() {
  if ! fn update context provider oracle; then
    printf 'Could not set the current Fn context provider to oracle.\n' >&2
    return 1
  fi
}

set_oci_environment() {
  local compartment_id compartments namespace
  # Accept the older script-specific overrides during setup; scripts consume
  # only the shared OCI_COMPARTMENT_ID afterward.
  compartment_id="${OCI_COMPARTMENT_ID:-${OCI_FN_COMPARTMENT_ID:-${OCI_BUCKET_COMPARTMENT_ID:-${OCI_SCHEDULE_COMPARTMENT_ID:-}}}}"
  if [ -z "$compartment_id" ]; then
    compartments="$(oci iam compartment list --compartment-id-in-subtree true \
      --name TraficInfo --lifecycle-state ACTIVE --all)" || return
    compartment_id="$(jq -er '
        .data | if length == 1 then .[0].id else
          error("Expected exactly one active TraficInfo compartment") end' \
      <<< "$compartments")" || return
  fi
  case "$compartment_id" in
    ocid1.compartment.*) ;;
    *) printf 'Invalid OCI_COMPARTMENT_ID.\n' >&2; return 1 ;;
  esac
  namespace="$(oci os ns get --query data --raw-output)" || return
  if [ -z "$namespace" ] || [ "$namespace" = null ]; then
    printf 'OCI did not return an Object Storage namespace.\n' >&2
    return 1
  fi
  export OCI_COMPARTMENT_ID="$compartment_id"
  export OCI_OBJECT_STORAGE_NAMESPACE="$namespace"
  export OCI_FN_APPLICATION_NAME=3MOpenDataProcessor
  printf 'OCI_COMPARTMENT_ID=%s\nOCI_OBJECT_STORAGE_NAMESPACE=%s\n' \
    "$OCI_COMPARTMENT_ID" "$OCI_OBJECT_STORAGE_NAMESPACE"
}

set_fn_compartment_id() {
  fn update context oracle.compartment-id "$OCI_COMPARTMENT_ID"
}

set_fn_api_url() {
  local region

  if ! region=$(awk -F= '/^[[:space:]]*region[[:space:]]*=/ {gsub(/[[:space:]]/, "", $2); print $2; exit}' "$OCI_CLI_CONFIG_FILE"); then
    printf 'Could not read the OCI region from %s.\n' "$OCI_CLI_CONFIG_FILE" >&2
    return 1
  fi
  case "$region" in
    ''|*[!a-z0-9-]*)
      printf 'Could not read a valid OCI region from %s.\n' "$OCI_CLI_CONFIG_FILE" >&2
      return 1
      ;;
  esac

  if ! fn update context api-url "https://functions.${region}.oci.oraclecloud.com"; then
    printf 'Could not set the Fn API URL for region %s.\n' "$region" >&2
    return 1
  fi
}

list_fn_apps() {
  printf 'List of Fn applications deployed:\n'
  if ! fn list apps; then
    printf 'Could not list deployed Fn applications.\n' >&2
    return 1
  fi
}

setup_project_environment() {
  set_project_paths || return 1
  set_java_home || return 1
  set_oci_config || return 1
  set_oci_environment || return 1
  set_fn_oracle_provider || return 1
  set_fn_compartment_id || return 1
  set_fn_api_url || return 1
  list_fn_apps
}

setup_project_environment "$@"
