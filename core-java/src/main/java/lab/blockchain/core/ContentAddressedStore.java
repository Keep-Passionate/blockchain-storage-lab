package lab.blockchain.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A tiny local stand-in for the IPFS part of the architecture.
 *
 * The object is addressed by its content hash. The CID format is deliberately
 * explicit (sha256:<hex>) so nobody mistakes this teaching adapter for a full
 * IPFS CID implementation. The adapter can later be replaced by an IPFS client
 * without changing TraceStorageService.
 */
public final class ContentAddressedStore implements ObjectStore {
    private final Path root;

    public ContentAddressedStore(Path root) throws IOException {
        this.root = root;
        Files.createDirectories(root.resolve("objects"));
        Files.createDirectories(root.resolve("pins"));
    }

    public StoredObject put(byte[] content) throws IOException {
        Objects.requireNonNull(content, "content");
        String sha256 = Hashing.sha256Hex(content);
        String cid = "sha256:" + sha256;
        Path object = objectPath(sha256);
        Files.createDirectories(object.getParent());
        if (!Files.exists(object)) {
            Files.write(object, content);
        }
        return new StoredObject(cid, sha256, content.length);
    }

    public byte[] get(String cid) throws IOException {
        String sha256 = parseSha256(cid);
        Path object = objectPath(sha256);
        if (!Files.exists(object)) {
            throw new IOException("object not found: " + cid);
        }
        byte[] content = Files.readAllBytes(object);
        if (!Hashing.sha256Hex(content).equals(sha256)) {
            throw new IOException("content hash mismatch: " + cid);
        }
        return content;
    }

    public void pin(String cid) throws IOException {
        String sha256 = parseSha256(cid);
        get(cid); // fail before creating a pin for an unknown object
        Files.writeString(root.resolve("pins").resolve(sha256 + ".pin"), cid);
    }

    public boolean exists(String cid) {
        try {
            return Files.exists(objectPath(parseSha256(cid)));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private Path objectPath(String sha256) {
        return root.resolve("objects").resolve(sha256.substring(0, 2)).resolve(sha256);
    }

    private static String parseSha256(String cid) {
        if (cid == null || !cid.startsWith("sha256:")) {
            throw new IllegalArgumentException("unsupported CID: " + cid);
        }
        String value = cid.substring("sha256:".length());
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid sha256 CID: " + cid);
        }
        return value;
    }
}
