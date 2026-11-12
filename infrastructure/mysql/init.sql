CREATE TABLE IF NOT EXISTS trace_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trace_id VARCHAR(128) NOT NULL,
    object_id VARCHAR(128) NOT NULL,
    stage VARCHAR(32) NOT NULL,
    operator_id VARCHAR(128) NOT NULL,
    content_cid VARCHAR(256) NOT NULL,
    metadata_hash VARCHAR(128) NOT NULL,
    block_hash VARCHAR(128),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_trace_stage (trace_id, stage)
);
