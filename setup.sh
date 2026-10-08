#!/usr/bin/env bash
# Source this file to keep JAVA_HOME and OCI_CLI_CONFIG_FILE in your shell:
#   source ./setup.sh

set_java_home() {
  local jdk_home javac_version

  if ! jdk_home=$(/usr/libexec/java_home -v 25 2>/dev/null); then
    printf 'JDK 25 is required but is not installed.\n' >&2
    return 1
  fi

  if [ ! -x "$jdk_home/bin/javac" ]; then
    printf 'JDK 25 is required, but %s has no javac executable.\n' "$jdk_home" >&2
    return 1
  fi

  if ! javac_version=$("$jdk_home/bin/javac" -version 2>&1); then
    printf 'Could not check the JDK version at %s: %s\n' "$jdk_home" "$javac_version" >&2
    return 1
  fi
  case "$javac_version" in
    'javac 25'|'javac 25.'*) ;;
    *)
      printf 'Expected JDK 25, but found %s.\n' "$javac_version" >&2
      return 1
      ;;
  esac

  export JAVA_HOME="$jdk_home"
  printf 'JAVA_HOME=%s\n' "$JAVA_HOME"
}

install_fn_cli_if_missing() {
  if command -v fn >/dev/null 2>&1; then
    return 0
  fi

  if ! command -v brew >/dev/null 2>&1; then
    printf 'Homebrew is required to install the Fn Project CLI.\n' >&2
    return 1
  fi

  if ! brew install fn; then
    printf 'Homebrew failed to install the Fn Project CLI.\n' >&2
    return 1
  fi

  if ! command -v fn >/dev/null 2>&1; then
    printf 'Fn Project CLI was installed, but fn is not available in PATH.\n' >&2
    return 1
  fi
}

install_oci_cli_if_missing() {
  if command -v oci >/dev/null 2>&1; then
    return 0
  fi

  if ! command -v brew >/dev/null 2>&1; then
    printf 'Homebrew is required to install the OCI CLI.\n' >&2
    return 1
  fi

  if ! brew install oci-cli; then
    printf 'Homebrew failed to install the OCI CLI.\n' >&2
    return 1
  fi

  if ! command -v oci >/dev/null 2>&1; then
    printf 'OCI CLI was installed, but oci is not available in PATH.\n' >&2
    return 1
  fi
}

set_oci_config() {
  if [ ! -f "$HOME/.oci/config.arnaudq" ]; then
    printf 'OCI config file not found: %s/.oci/config.arnaudq\n' "$HOME" >&2
    return 1
  fi

  export OCI_CLI_CONFIG_FILE="$HOME/.oci/config.arnaudq"
  printf 'OCI_CLI_CONFIG_FILE=%s\n' "$OCI_CLI_CONFIG_FILE"
}

set_fn_oracle_provider() {
  if ! fn update context provider oracle; then
    printf 'Could not set the current Fn context provider to oracle.\n' >&2
    return 1
  fi
}

set_fn_compartment_id() {
  local compartment_count compartment_id

  if ! compartment_count=$(oci iam compartment list \
    --config-file "$OCI_CLI_CONFIG_FILE" \
    --compartment-id-in-subtree true \
    --name TraficInfo \
    --lifecycle-state ACTIVE \
    --all \
    --query 'length(data)' \
    --raw-output); then
    printf 'OCI CLI could not count active TraficInfo compartments using %s.\n' "$OCI_CLI_CONFIG_FILE" >&2
    return 1
  fi

  if [ "$compartment_count" != 1 ]; then
    printf 'Expected one active TraficInfo compartment, found %s.\n' "$compartment_count" >&2
    return 1
  fi

  if ! compartment_id=$(oci iam compartment list \
    --config-file "$OCI_CLI_CONFIG_FILE" \
    --compartment-id-in-subtree true \
    --name TraficInfo \
    --lifecycle-state ACTIVE \
    --all \
    --query 'data[0].id' \
    --raw-output); then
    printf 'OCI CLI could not retrieve the TraficInfo compartment OCID.\n' >&2
    return 1
  fi

  case "$compartment_id" in
    ocid1.compartment.*) ;;
    *)
      printf 'OCI CLI returned an invalid TraficInfo compartment OCID.\n' >&2
      return 1
      ;;
  esac

  if ! fn update context oracle.compartment-id "$compartment_id"; then
    printf 'Could not set oracle.compartment-id in the current Fn context.\n' >&2
    return 1
  fi
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
  set_java_home || return 1
  install_fn_cli_if_missing || return 1
  install_oci_cli_if_missing || return 1
  set_oci_config || return 1
  set_fn_oracle_provider || return 1
  set_fn_compartment_id || return 1
  set_fn_api_url || return 1
  list_fn_apps
}

setup_project_environment "$@"
