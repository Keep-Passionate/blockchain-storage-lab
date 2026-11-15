#!/usr/bin/env bash
# 文件功能：只读查询已有批次并核对 IPFS 原件；--rebuild 可从链补齐该批次数据库投影。
set -euo pipefail
if [[ "$#" -eq 0 || "${1:-}" == '--help' || "${1:-}" == '-h' ]]; then
  echo '用法: bash scripts/query-batch.sh <已有批次号> [--rebuild]'
  echo '例子: bash scripts/query-batch.sh DEMOTEA-2026-000001'
  echo '默认只读查询；--rebuild 只补齐投影，不覆盖冲突数据，不修改提交证据。'
  echo '结果保存到 runs/query；本脚本使用已编译的 Java 类，不重复构建全项目。'
  exit 0
fi
batch_id="$1"
if [[ ! "$batch_id" =~ ^[A-Z0-9]{2,12}-[0-9]{4}-[0-9]{6}$ || "$#" -gt 2 ]]; then
  echo '批次号格式应为区域代码-年份-六位序号；使用 --help 查看用法。' >&2
  exit 2
fi
if [[ "$#" -eq 2 && "$2" != '--rebuild' ]]; then
  echo '第二个参数只支持 --rebuild。' >&2
  exit 2
fi
source "$(dirname "$0")/lab-env.sh"
cd "$LAB_ROOT"
query_args="$batch_id"
if [[ "$#" -eq 2 ]]; then query_args="$query_args --rebuild"; fi
mvn -q -pl backend-java exec:java \
  -Dexec.mainClass=lab.blockchain.backend.provenance.ProvenanceQueryMain \
  -Dexec.args="$query_args"
