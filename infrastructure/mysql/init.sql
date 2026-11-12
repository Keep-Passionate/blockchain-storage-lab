-- 文件功能：初始化链下索引和普通业务数据使用的 MySQL 表。
CREATE TABLE IF NOT EXISTS trace_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trace_id VARCHAR(128) NOT NULL,
    object_id VARCHAR(128) NOT NULL,
    stage VARCHAR(32) NOT NULL,
    operator_id VARCHAR(128) NOT NULL,
    content_cid VARCHAR(256) NOT NULL,
    metadata_hash VARCHAR(128) NOT NULL,
    fabric_tx_id VARCHAR(128),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_trace_stage (trace_id, stage)
);
