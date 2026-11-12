package lab.blockchain.core;

/** Combined view returned by the service, joining on-chain facts and off-chain bytes. */
public record TraceRecord(TraceEvent event, long blockIndex, String blockHash, boolean fileAvailable) {}
