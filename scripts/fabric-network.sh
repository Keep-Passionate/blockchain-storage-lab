#!/usr/bin/env bash
# 文件功能：在服务器上启动 Fabric test-network，并部署本项目的 trace 链码。
set -euo pipefail

FABRIC_SAMPLES_DIR="${FABRIC_SAMPLES_DIR:-$HOME/xc/fabric-samples}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NETWORK_DIR="$FABRIC_SAMPLES_DIR/test-network"

if [[ ! -x "$NETWORK_DIR/network.sh" ]]; then
  echo "找不到 Fabric test-network: $NETWORK_DIR" >&2
  exit 1
fi

case "${1:-up}" in
  up)
    (cd "$NETWORK_DIR" && ./network.sh up createChannel -ca)
    ;;
  deploy)
    (cd "$NETWORK_DIR" && ./network.sh deployCC \
      -ccn trace \
      -ccp "$REPO_ROOT/chaincode-go" \
      -ccl go \
      -c mychannel)
    ;;
  deploy-provenance)
    # 单独命名的新链码，不关闭网络，不删除原 trace 账本。
    (cd "$NETWORK_DIR" && ./network.sh deployCC \
      -ccn provenance \
      -ccp "$REPO_ROOT/chaincode-go" \
      -ccl go \
      -ccv "${PROVENANCE_VERSION:-1.0}" \
      -ccs "${PROVENANCE_SEQUENCE:-1}" \
      -c mychannel)
    ;;
  down)
    (cd "$NETWORK_DIR" && ./network.sh down)
    ;;
  *)
    echo "用法: $0 {up|deploy|deploy-provenance|down}" >&2
    exit 2
    ;;
esac
