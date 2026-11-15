-- 文件功能：新增四层溯源、审核审计、链下请求证据和事件去重表；保留旧 trace_record。
-- 用已选择本项目数据库的 mysql 客户端执行。只 CREATE IF NOT EXISTS，不删除既有数据。
-- *_json 保留完整事实，普通列提供关联和索引；所有 ID 大小写敏感，避免链/库键语义不同。

CREATE TABLE IF NOT EXISTS p_plot (
    plot_id VARCHAR(128) COLLATE utf8mb4_bin PRIMARY KEY,
    region_code VARCHAR(12) COLLATE utf8mb4_bin NOT NULL,
    crop TEXT NOT NULL,
    variety TEXT NOT NULL,
    growth_stage TEXT NOT NULL,
    altitude_m DOUBLE NOT NULL,
    environment_text TEXT NOT NULL,
    record_json JSON NOT NULL,
    fabric_tx_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    KEY idx_plot_region (region_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS p_batch (
    batch_id VARCHAR(128) COLLATE utf8mb4_bin PRIMARY KEY,
    region_code VARCHAR(12) COLLATE utf8mb4_bin NOT NULL,
    production_year INT NOT NULL,
    sequence_no INT NOT NULL,
    plot_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    started_at VARCHAR(40) NOT NULL,
    record_json JSON NOT NULL,
    fabric_tx_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    UNIQUE KEY uk_batch_number (region_code, production_year, sequence_no),
    UNIQUE KEY uk_batch_plot (batch_id, plot_id),
    CONSTRAINT fk_batch_plot FOREIGN KEY (plot_id) REFERENCES p_plot(plot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS p_operation (
    operation_id VARCHAR(128) COLLATE utf8mb4_bin PRIMARY KEY,
    batch_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    plot_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    operation_type VARCHAR(32) NOT NULL,
    performed_at VARCHAR(40) NOT NULL,
    operator_id VARCHAR(128) NOT NULL,
    description_text TEXT NOT NULL,
    rule_version VARCHAR(64) NOT NULL,
    record_json JSON NOT NULL,
    fabric_tx_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    KEY idx_operation_batch_time (batch_id, performed_at),
    CONSTRAINT fk_operation_batch_plot FOREIGN KEY (batch_id, plot_id)
        REFERENCES p_batch(batch_id, plot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS p_inspection (
    inspection_id VARCHAR(128) COLLATE utf8mb4_bin PRIMARY KEY,
    batch_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    report_cid VARCHAR(256) NOT NULL,
    report_sha256 CHAR(64) COLLATE utf8mb4_bin NOT NULL,
    conclusion VARCHAR(16) NOT NULL,
    lab_name TEXT NOT NULL,
    tested_at VARCHAR(40) NOT NULL,
    summary_text TEXT NOT NULL,
    record_json JSON NOT NULL,
    fabric_tx_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    UNIQUE KEY uk_inspection_batch (inspection_id, batch_id),
    KEY idx_inspection_batch_conclusion (batch_id, conclusion),
    CONSTRAINT fk_inspection_batch FOREIGN KEY (batch_id) REFERENCES p_batch(batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS p_review (
    review_id VARCHAR(128) COLLATE utf8mb4_bin PRIMARY KEY,
    batch_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    inspection_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    decision VARCHAR(16) NOT NULL,
    comment_text TEXT NOT NULL,
    record_json JSON NOT NULL,
    fabric_tx_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    KEY idx_review_batch (batch_id),
    CONSTRAINT fk_review_inspection_batch FOREIGN KEY (inspection_id, batch_id)
        REFERENCES p_inspection(inspection_id, batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS p_audit (
    audit_id VARCHAR(128) COLLATE utf8mb4_bin PRIMARY KEY,
    batch_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    record_ids_json JSON NOT NULL,
    evidence_cid VARCHAR(256) NOT NULL,
    evidence_sha256 CHAR(64) COLLATE utf8mb4_bin NOT NULL,
    conclusion VARCHAR(16) NOT NULL,
    comment_text TEXT NOT NULL,
    record_json JSON NOT NULL,
    fabric_tx_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    KEY idx_audit_batch_conclusion (batch_id, conclusion),
    CONSTRAINT fk_audit_batch FOREIGN KEY (batch_id) REFERENCES p_batch(batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS p_alert (
    alert_id VARCHAR(128) COLLATE utf8mb4_bin PRIMARY KEY,
    batch_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    source_kind VARCHAR(16) NOT NULL,
    source_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    delivery_status VARCHAR(32) NOT NULL,
    recipient TEXT NOT NULL,
    comment_text TEXT NOT NULL,
    record_json JSON NOT NULL,
    fabric_tx_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    KEY idx_alert_batch_source (batch_id, source_kind, source_id),
    CONSTRAINT fk_alert_batch FOREIGN KEY (batch_id) REFERENCES p_batch(batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 原始请求不是缓存：失败的操作不会出现在 Fabric 有效状态中，必须独立保存和备份。
-- 不设置 batch 外键，因为无效批次号本身也可能是需要保留的拒绝证据。
CREATE TABLE IF NOT EXISTS p_submission (
    request_id VARCHAR(128) COLLATE utf8mb4_bin PRIMARY KEY,
    record_kind VARCHAR(16) NOT NULL,
    record_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    batch_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    raw_payload LONGTEXT NOT NULL,
    payload_sha256 CHAR(64) COLLATE utf8mb4_bin NOT NULL,
    submission_status VARCHAR(16) NOT NULL,
    reason_codes_json JSON NOT NULL,
    fabric_tx_id VARCHAR(128) COLLATE utf8mb4_bin NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    KEY idx_submission_batch_status (batch_id, submission_status),
    KEY idx_submission_record (record_kind, record_id),
    CONSTRAINT chk_submission_status CHECK
        (submission_status IN ('PENDING', 'COMMITTED', 'REJECTED', 'UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 固定一个 channel + 一个 chaincode 的教学库；未来多通道要把通道/链码加入主键。
CREATE TABLE IF NOT EXISTS p_event_inbox (
    fabric_tx_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    event_name VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    block_number BIGINT NOT NULL,
    record_kind VARCHAR(16) NOT NULL,
    record_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    event_json JSON NOT NULL,
    processed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (fabric_tx_id, event_name),
    KEY idx_event_block (block_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
