#!/usr/bin/env bash
# Source after scripts/setup.sh to reuse its OCI API-key configuration in OpenTofu.

traficinfo_export_oci_provider_auth() {
  local exports
  : "${OCI_CLI_CONFIG_FILE:?Run source ./scripts/setup.sh first}" || return 1
  exports="$(python3 - "$OCI_CLI_CONFIG_FILE" "${OCI_CLI_PROFILE:-DEFAULT}" <<'PY'
import configparser
import pathlib
import shlex
import sys

config = configparser.ConfigParser(interpolation=None)
with pathlib.Path(sys.argv[1]).expanduser().open() as source:
    config.read_file(source)
profile = config[sys.argv[2]]
values = {
    "OCI_AUTH": "APIKey",
    "OCI_TENANCY_OCID": profile["tenancy"],
    "OCI_USER_OCID": profile["user"],
    "OCI_FINGERPRINT": profile["fingerprint"],
    "OCI_PRIVATE_KEY_PATH": str(pathlib.Path(profile["key_file"]).expanduser()),
    "OCI_PRIVATE_KEY_PASSWORD": profile.get("pass_phrase", ""),
    "OCI_PRIVATE_KEY": "",
    "OCI_CONFIG_FILE_PROFILE": "",
}
for name, value in values.items():
    print(f"export {name}={shlex.quote(value)}")
PY
)" || return 1
  eval "$exports"
}

traficinfo_export_oci_provider_auth
