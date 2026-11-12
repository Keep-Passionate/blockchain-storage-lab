#!/usr/bin/env bash
# 文件功能：在实验室服务器下载固定版本的 Fabric 二进制、Docker 镜像和 samples。
set -euo pipefail

FABRIC_VERSION="${FABRIC_VERSION:-2.5.16}"
FABRIC_CA_VERSION="${FABRIC_CA_VERSION:-1.5.17}"
FABRIC_HOME="${FABRIC_HOME:-$HOME/xc}"
: "${FABRIC_SAMPLES_REF:?请设置 FABRIC_SAMPLES_REF 为已审核的 fabric-samples 提交号}"

mkdir -p "$FABRIC_HOME"
cd "$FABRIC_HOME"

# 使用官方安装脚本；下载动作只在服务器执行。
curl -fsSL https://raw.githubusercontent.com/hyperledger/fabric/main/scripts/install-fabric.sh \
  -o install-fabric-upstream.sh
chmod +x install-fabric-upstream.sh
./install-fabric-upstream.sh \
  --fabric-version "$FABRIC_VERSION" \
  --ca-version "$FABRIC_CA_VERSION" \
  docker binary samples

cd fabric-samples
git fetch --depth=1 origin "$FABRIC_SAMPLES_REF"
git checkout --detach "$FABRIC_SAMPLES_REF"
printf 'Fabric=%s CA=%s samples=%s\n' "$FABRIC_VERSION" "$FABRIC_CA_VERSION" "$FABRIC_SAMPLES_REF"
