/** 文件功能：定义查询结果，将链上事实与链下文件可用性合并返回。 */
package lab.blockchain.core;

/** Combined view returned by the service, joining on-chain facts and off-chain bytes. */
public record TraceRecord(TraceEvent event, long blockIndex, String blockHash, boolean fileAvailable) {}
