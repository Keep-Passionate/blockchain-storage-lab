#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$repo_root/core-java/out"
find "$repo_root/core-java/src/main/java" -name '*.java' -print0 \
  | xargs -0 javac -encoding UTF-8 -d "$repo_root/core-java/out"
java -cp "$repo_root/core-java/out" lab.blockchain.core.DemoMain "${1:-$repo_root/runs/demo}"
