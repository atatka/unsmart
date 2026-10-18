#!/usr/bin/env bash
# Disable every package listed in a debloat file on the TV (one package per line, # comments allowed).
# Packages are disabled for user 0, never uninstalled. Undo with tools/restore.sh <same file>.
#
# Usage: tools/disable.sh debloat/tcl-smart-tv-pro-g08.txt      (TV_HOST defaults to 192.168.178.36)
set -euo pipefail
cd "$(dirname "$0")/.."
LIST="${1:?usage: disable.sh <debloat list>}"
{
  grep -vE '^\s*(#|$)' "$LIST" | sed -E 's/[[:space:]]+#.*$//' | awk '{print "pm disable-user --user 0 " $1}'
  echo "pm list packages -d"
} | ./tv --batch
