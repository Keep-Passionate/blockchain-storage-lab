# Infrastructure notes

`docker-compose.yml` starts only the learning dependencies: MySQL, Redis and a single Kubo IPFS node. It intentionally does not pretend that three commands reproduce a Fabric network. Fabric needs organization identities, channel configuration, orderer/peer certificates, chaincode packaging and a version-matched test-network setup.

Local smoke test:

```bash
docker compose --env-file .env up -d
docker compose ps
curl http://127.0.0.1:5001/api/v0/version
```

On the lab server, copy this directory, set a private `.env`, and bind ports only as the server policy allows. Do not commit passwords or certificates.

When the Java service is added, its default endpoints should point to `mysql:3306`, `redis:6379`, and the IPFS API at `http://ipfs:5001/api/v0` from inside the Compose network.
