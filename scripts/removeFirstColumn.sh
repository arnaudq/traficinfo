#!/usr/bin/env bash
# Remove the first CSV column and write a sibling file with an _nc suffix.
set -euo pipefail

usage() {
  printf 'Usage: %s <file.csv>\n' "$0" >&2
}

main() {
  if [ "$#" -ne 1 ]; then
    usage
    return 1
  fi

  local input="$1"
  if [[ "$input" != *.csv ]]; then
    printf 'Error: input filename must end in .csv\n' >&2
    return 1
  fi
  if [ ! -f "$input" ] || [ ! -r "$input" ]; then
    printf 'Error: cannot read file: %s\n' "$input" >&2
    return 1
  fi

  local output="${input%.csv}_nc.csv"
  python3 - "$input" "$output" <<'PY'
import csv
import sys

with open(sys.argv[1], encoding="utf-8-sig", newline="") as source:
    with open(sys.argv[2], "w", encoding="utf-8", newline="") as destination:
        writer = csv.writer(destination)
        for row in csv.reader(source):
            writer.writerow(row[1:])
PY
  printf 'Created %s\n' "$output"
}

main "$@"
