# Fabric adapter boundary

The first iteration uses `core-java` so that block formation and content-addressed storage can be inspected without a Fabric deployment. When the teacher confirms the target Fabric version, add a version-locked Fabric test network here and implement a Java `FabricClient` adapter.

Do not copy a random `test-network` from the internet into the repository. Record the exact Fabric release, Docker image digests, organization topology and channel/chaincode commands in a dated protocol file.
