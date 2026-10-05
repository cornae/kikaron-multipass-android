#!/usr/bin/env bash
# Brand an upstream bitwarden/android checkout as Kikaron Multipass (kikaron/brand.py),
# optionally refreshing the icon from a Kikaron checkout first:
#   kikaron/brand.sh [path/to/kikaron/multipass/brand/icon-square.svg]
set -euo pipefail
cd "$(dirname "$0")/.."
if [ "${1:-}" ]; then cp "$1" kikaron/icon.svg; fi
python3 kikaron/brand.py
