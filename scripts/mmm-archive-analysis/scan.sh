#!/usr/bin/env bash
set -euo pipefail

scan_mmm_archives() {
  python3 "${TRAFICINFO_ROOT:?Run source ./scripts/setup.sh first}/scripts/mmm-archive-analysis/scan.py" "$@"
}

scan_mmm_archives "$@"
