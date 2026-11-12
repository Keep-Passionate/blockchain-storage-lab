# 服务器部署

本仓库不内置账号、密码、证书或服务器地址。实验室服务器的工作根目录约定为 `xc`，拿到当前 SSH 信息后：

```bash
export LAB_SERVER='user@lab-server'
export LAB_PATH='~/xc/blockchain-storage-lab'
./scripts/deploy-server.sh
./scripts/verify-server.sh
```

服务器端准备 JDK 17、Go、Docker、Docker Compose、rsync 和 Fabric CLI。主链路直接使用服务器上的 Kubo IPFS 与 Fabric test-network；本地不启动这些服务。部署脚本默认跳过 `.git`、运行产物和 Maven target，避免把本地缓存传上服务器。

Fabric 的安装、启动和链码部署由 `install-fabric.sh`、`fabric-network.sh` 完成。Fabric samples 的具体提交号必须单独记录在服务器实验记录中，避免用随时变化的 `main` 分支冒充可复现版本。
