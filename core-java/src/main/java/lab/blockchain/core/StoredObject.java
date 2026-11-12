/** 文件功能：定义链下对象写入后返回的 CID、摘要和大小元数据。 */
package lab.blockchain.core;

/** Metadata returned after an off-chain object is stored. */
public record StoredObject(String cid, String sha256, long size) {}
