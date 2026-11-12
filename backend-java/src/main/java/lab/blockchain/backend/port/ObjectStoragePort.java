/** 文件功能：定义业务层调用 IPFS/链下对象存储的端口。 */
package lab.blockchain.backend.port;

import lab.blockchain.core.StoredObject;

import java.io.IOException;

/** The business layer does not depend directly on IPFS SDK classes. */
public interface ObjectStoragePort {
    StoredObject add(byte[] bytes) throws IOException;
    byte[] cat(String cid) throws IOException;
    void pin(String cid) throws IOException;
}
