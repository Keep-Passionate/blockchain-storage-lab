package lab.blockchain.core;

/** Metadata returned after an off-chain object is stored. */
public record StoredObject(String cid, String sha256, long size) {}
