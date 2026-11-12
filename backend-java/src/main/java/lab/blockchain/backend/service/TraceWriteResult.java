/** 文件功能：封装一次 IPFS 上传和 Fabric 提交成功后的可读结果。 */
package lab.blockchain.backend.service;

import lab.blockchain.core.StoredObject;
import lab.blockchain.core.TraceEvent;

/** 主链路返回的事件和 IPFS 对象元数据。 */
public record TraceWriteResult(TraceEvent event, StoredObject storedObject) {}
