#!/usr/bin/env bash
set -euo pipefail

generate_map_templates() {
  python3 "${TRAFICINFO_ROOT:?Run source ./scripts/setup.sh first}/scripts/generate-map-templates.py" "$@"
}

generate_map_templates "$@"
