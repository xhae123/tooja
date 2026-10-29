-- Owner console: read-only operator sessions plus best-effort operational records.
-- Operational tables store epoch milliseconds so range filters and bucket aggregates stay numeric.
CREATE TABLE owner_sessions (
    token_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    csrf VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at VARCHAR(40) NOT NULL, expires_at VARCHAR(40) NOT NULL, revoked_at VARCHAR(40),
    client_ip VARCHAR(45) NOT NULL, user_agent VARCHAR(512)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE activity_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, ts BIGINT NOT NULL,
    request_id VARCHAR(44) NOT NULL, method VARCHAR(8) NOT NULL, route VARCHAR(128) NOT NULL,
    status SMALLINT NOT NULL, duration_ms INT NOT NULL,
    actor_role VARCHAR(16), account_id VARCHAR(64),
    action VARCHAR(48) NOT NULL, error_code VARCHAR(64),
    team_id INT, investment_id VARCHAR(36), amount INT,
    origin VARCHAR(255), client_ip VARCHAR(45) NOT NULL, remote_addr VARCHAR(45) NOT NULL, user_agent VARCHAR(512),
    INDEX ix_activity_ts (ts), INDEX ix_activity_action_ts (action, ts),
    INDEX ix_activity_account_ts (account_id, ts), INDEX ix_activity_ip_ts (client_ip, ts),
    INDEX ix_activity_status_ts (status, ts)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE infra_samples (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, ts BIGINT NOT NULL,
    source VARCHAR(8) NOT NULL, metric VARCHAR(64) NOT NULL, sample_value DOUBLE NOT NULL, dims VARCHAR(255),
    INDEX ix_samples_metric_ts (metric, ts), INDEX ix_samples_source_ts (source, ts), INDEX ix_samples_ts (ts)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE system_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, ts BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL, detail TEXT,
    INDEX ix_events_ts (ts), INDEX ix_events_type_ts (type, ts)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
