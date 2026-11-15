#!/usr/bin/env bash
# 文件功能：为本项目命令加载服务器工具与未入Git的数据库凭据；请用source调用。
LAB_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export LAB_ROOT
export PATH="$HOME/xc/tools/maven-3.10.0/bin:$HOME/xc/tools/go/bin:$HOME/xc/fabric-samples/bin:$PATH"
export FABRIC_SAMPLES_DIR="${FABRIC_SAMPLES_DIR:-$HOME/xc/fabric-samples}"
export PROVENANCE_CHAINCODE="${PROVENANCE_CHAINCODE:-provenance}"
if [[ ! -f "$LAB_ROOT/infrastructure/.env" ]]; then
  echo '缺少服务器 infrastructure/.env（请勿提交密码到Git）' >&2
  return 1
fi
set -a
source "$LAB_ROOT/infrastructure/.env"
set +a
: "${MYSQL_DATABASE:?缺少MYSQL_DATABASE}" "${MYSQL_USER:?缺少MYSQL_USER}" "${MYSQL_PASSWORD:?缺少MYSQL_PASSWORD}"
# 此URL仅访问本机开发容器；跨机部署必须配置TLS验证，不能直接复用该开发URL。
export MYSQL_URL="${MYSQL_URL:-jdbc:mysql://127.0.0.1:3307/$MYSQL_DATABASE?allowPublicKeyRetrieval=true&useSSL=false&characterEncoding=UTF-8}"
export IPFS_API="${IPFS_API:-http://127.0.0.1:5001}"
