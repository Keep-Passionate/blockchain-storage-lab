/** 文件功能：验证离线内容寻址存储、pin 和教学账本摘要完整性。 */
package lab.blockchain.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class StorageFlowTest {
    @Test
    void contentAddressedStoreIsDeterministicAndVerifiable() throws Exception {
        var root = Files.createTempDirectory("bsl-store-");
        var store = new ContentAddressedStore(root);
        var first = store.put("same bytes".getBytes(StandardCharsets.UTF_8));
        var second = store.put("same bytes".getBytes(StandardCharsets.UTF_8));

        assertEquals(first.cid(), second.cid());
        assertArrayEquals("same bytes".getBytes(StandardCharsets.UTF_8), store.get(first.cid()));
        store.pin(first.cid());
        assertTrue(store.exists(first.cid()));
    }

    @Test
    void changingAnEventBreaksTheRecordedBlockHash() throws Exception {
        var root = Files.createTempDirectory("bsl-ledger-");
        var store = new ContentAddressedStore(root);
        var ledger = new BlockchainLedger();
        var service = new TraceStorageService(store, ledger);
        service.append("trace-1", "object-1", Stage.PLANTING, "operator-1", "one",
                "file".getBytes(StandardCharsets.UTF_8));

        assertTrue(service.verifyLedger());
        var original = ledger.blocks().get(1);
        var alteredEvent = new TraceEvent(original.events().get(0).traceId(), "object-1", Stage.PICKING,
                "operator-1", Instant.now(), original.events().get(0).contentCid(),
                original.events().get(0).metadataHash(), "tampered");
        var altered = Block.create(original.index(), original.createdAt(), original.previousHash(),
                java.util.List.of(alteredEvent));
        var blocks = new java.util.ArrayList<>(ledger.blocks());
        blocks.set(1, altered);
        assertNotEquals(original.hash(), altered.hash());
    }
}
