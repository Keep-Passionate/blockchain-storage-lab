package lab.blockchain.backend.port;

import lab.blockchain.core.StoredObject;

import java.io.IOException;

/** The business layer does not depend directly on IPFS SDK classes. */
public interface ObjectStoragePort {
    StoredObject add(byte[] bytes) throws IOException;
    byte[] cat(String cid) throws IOException;
    void pin(String cid) throws IOException;
}
