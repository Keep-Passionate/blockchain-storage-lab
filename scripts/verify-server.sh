#!/usr/bin/env bash
set -euo pipefail
: "${LAB_SERVER:?Set LAB_SERVER}"
: "${LAB_PATH:?Set LAB_PATH}"
ssh "$LAB_SERVER" "cd '$LAB_PATH' && ./scripts/run-demo.sh"
