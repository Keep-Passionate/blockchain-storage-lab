#!/usr/bin/env bash
# 文件功能：通过 SSH 调用服务器端演示脚本验证部署结果。
set -euo pipefail
: "${LAB_SERVER:?Set LAB_SERVER}"
: "${LAB_PATH:?Set LAB_PATH}"
ssh "$LAB_SERVER" "cd '$LAB_PATH' && ./scripts/run-demo.sh"
