#!/usr/bin/env bash
# Check that the TV is still a dumb TV and repair what drifted, e.g. after a firmware update.
# Usage: tools/check.sh [--dry-run]      (TV_HOST defaults to 192.168.178.36)
cd "$(dirname "$0")/.."
export TV_HOST="${TV_HOST:-192.168.178.36}"
exec tools/venv/bin/python -I tools/check.py "$@"
