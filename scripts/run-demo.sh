#!/usr/bin/env bash
# 文件功能：在 Linux 服务器上直接编译并运行 Java 最小闭环演示。
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$repo_root/core-java/out"
find "$repo_root/core-java/src/main/java" -name '*.java' -print0 \
  | xargs -0 javac -encoding UTF-8 -d "$repo_root/core-java/out"
java -cp "$repo_root/core-java/out" lab.blockchain.core.DemoMain "${1:-$repo_root/runs/demo}"
