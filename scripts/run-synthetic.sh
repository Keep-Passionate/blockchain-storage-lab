#!/usr/bin/env bash
# 文件功能：构建Java并在服务器执行一次可审计的虚拟四层业务实验。
set -euo pipefail
sequence="${1:?用法: bash scripts/run-synthetic.sh <1..999999的新序号>}"
if [[ ! "$sequence" =~ ^[0-9]{1,6}$ ]]; then echo '序号必须为1..999999' >&2; exit 2; fi
source "$(dirname "$0")/lab-env.sh"
cd "$LAB_ROOT"
mvn -q -pl backend-java -am install
mvn -q -pl backend-java exec:java \
  -Dexec.mainClass=lab.blockchain.backend.provenance.SyntheticScenarioMain \
  -Dexec.args="$sequence"
