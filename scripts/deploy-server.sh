#!/usr/bin/env bash
# 文件功能：将代码同步到实验室服务器 xc 目录，不上传运行产物。
set -euo pipefail
: "${LAB_SERVER:?Set LAB_SERVER, for example user@server}"
: "${LAB_PATH:?Set LAB_PATH, for example /srv/blockchain-storage-lab}"
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
rsync -az --delete --exclude .git --exclude runs --exclude target \
  "$repo_root/" "$LAB_SERVER:$LAB_PATH/"
printf 'Uploaded source to %s:%s\n' "$LAB_SERVER" "$LAB_PATH"
