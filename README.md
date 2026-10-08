# TraficInfo

## Setup

Install JDK 25 and Homebrew, and place your OCI configuration at `~/.oci/config.arnaudq`. Then, from the project root, run:

```sh
source ./setup.sh
```

Source the script so `JAVA_HOME` and `OCI_CLI_CONFIG_FILE` remain set in your current shell. The script installs the Fn Project CLI and OCI CLI through Homebrew if needed, configures the current Fn context for the active `TraficInfo` compartment and its regional Functions API URL, then lists the deployed Fn applications.
