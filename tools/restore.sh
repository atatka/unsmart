#!/usr/bin/env bash
# Re-enable every package listed in a debloat file and make the stock Google TV launcher home again.
# DumbTV stays installed; remove it with:  ./tv 'pm uninstall com.dumbtv.home'
#
# Usage: tools/restore.sh debloat/tcl-smart-tv-pro-g08.txt      (TV_HOST defaults to 192.168.178.36)
set -euo pipefail
cd "$(dirname "$0")/.."
LIST="${1:?usage: restore.sh <debloat list>}"
STOCK="${STOCK_LAUNCHER:-com.google.android.apps.tv.launcherx}"
{
  grep -vE '^\s*(#|$)' "$LIST" | sed -E 's/[[:space:]]+#.*$//' | awk '{print "pm enable " $1}'
  echo "pm enable $STOCK"
  echo "cmd role add-role-holder --user 0 android.app.role.HOME $STOCK 0"
  echo "cmd package set-home-activity --user 0 $STOCK"
  echo "pm list packages -d"
} | ./tv --batch
echo "Restored. Press Home on the remote."
