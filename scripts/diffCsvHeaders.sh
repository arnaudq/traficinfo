#!/usr/bin/env bash
# List labels added or removed between two CSV files in ~/Downloads.
# The first file is the baseline; column order and duplicates are ignored.
# Exit status: 0 = same labels, 1 = added/removed labels, 2 = error.
set -euo pipefail

usage() {
  printf 'Usage: %s <first.csv> <second.csv>\n' "$0" >&2
}

compare_labels() {
  python3 - "$1" "$2" <<'PY'
import csv
import sys


def read_labels(path):
    with open(path, encoding="utf-8-sig", newline="") as source:
        sample = source.readline()
        source.seek(0)
        try:
            dialect = csv.Sniffer().sniff(sample, delimiters=",;\t|")
        except csv.Error:
            dialect = csv.excel
        return set(next(csv.reader(source, dialect, strict=True), []))


try:
    first = read_labels(sys.argv[1])
    second = read_labels(sys.argv[2])
except (OSError, UnicodeError, csv.Error) as error:
    print(f"Error: {error}", file=sys.stderr)
    sys.exit(2)

added = sorted(second - first)
removed = sorted(first - second)
for title, labels in (("Added labels", added), ("Removed labels", removed)):
    print(f"{title}:")
    if labels:
        for label in labels:
            print(f"  {label!r}")
    else:
        print("  (none)")

sys.exit(1 if added or removed else 0)
PY
}

main() {
  if [ "$#" -ne 2 ]; then
    usage
    return 2
  fi

  local first="$HOME/Downloads/$1"
  local second="$HOME/Downloads/$2"
  local input
  for input in "$first" "$second"; do
    if [ ! -f "$input" ] || [ ! -r "$input" ]; then
      printf 'Error: cannot read file: %s\n' "$input" >&2
      return 2
    fi
  done

  compare_labels "$first" "$second"
}

main "$@"
