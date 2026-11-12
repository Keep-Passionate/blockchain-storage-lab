package lab.blockchain.core;

import java.io.IOException;

/** Storage port: production code can implement this with IPFS instead. */
public interface ObjectStore {
    StoredObject put(byte[] content) throws IOException;
    byte[] get(String cid) throws IOException;
    void pin(String cid) throws IOException;
    boolean exists(String cid);
}
