/** 文件功能：定义教学账本中的区块结构、Merkle 根和区块哈希计算。 */
package lab.blockchain.core;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/** One append-only block in the educational ledger. */
public record Block(
        long index,
        Instant createdAt,
        String previousHash,
        List<TraceEvent> events,
        String merkleRoot,
        String hash
) {
    public Block {
        events = List.copyOf(events);
    }

    public static Block create(long index, Instant createdAt, String previousHash, List<TraceEvent> events) {
        String merkleRoot = MerkleTree.root(events.stream().map(TraceEvent::eventHash).toList());
        String payload = index + "|" + createdAt + "|" + previousHash + "|" + merkleRoot + "|"
                + events.stream().map(TraceEvent::canonical).collect(Collectors.joining("||"));
        return new Block(index, createdAt, previousHash, events, merkleRoot, Hashing.sha256Hex(payload));
    }

    public boolean recomputesToHash() {
        return create(index, createdAt, previousHash, events).hash.equals(hash);
    }
}
