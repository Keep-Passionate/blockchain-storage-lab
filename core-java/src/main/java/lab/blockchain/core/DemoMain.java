package lab.blockchain.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Runs the complete file -> CID -> ledger -> query -> file verification loop. */
public final class DemoMain {
    private DemoMain() {}

    public static void main(String[] args) throws Exception {
        Path root = args.length == 0
                ? Files.createTempDirectory("blockchain-storage-demo-")
                : Path.of(args[0]);
        ContentAddressedStore store = new ContentAddressedStore(root.resolve("offchain"));
        BlockchainLedger ledger = new BlockchainLedger();
        TraceStorageService service = new TraceStorageService(store, ledger);

        byte[] photo = "demo tea photo bytes".getBytes(StandardCharsets.UTF_8);
        TraceRecord created = service.append("TRACE-001", "OBJECT-001", Stage.PLANTING,
                "student", "first planting record", photo);

        TraceRecord queried = service.query("TRACE-001").get(0);
        boolean fileRoundTrip = java.util.Arrays.equals(photo, service.fetchFile(queried.event()));

        System.out.println("root=" + root.toAbsolutePath());
        System.out.println("cid=" + created.event().contentCid());
        System.out.println("block=" + created.blockIndex() + " hash=" + created.blockHash());
        System.out.println("ledgerVerified=" + service.verifyLedger());
        System.out.println("fileRoundTripVerified=" + fileRoundTrip);
    }
}
