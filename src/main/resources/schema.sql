-- 会话历史与经验持久化表结构（MySQL / InnoDB / utf8mb4）
-- 由 Spring Boot spring.sql.init 在启动时自动执行（CREATE TABLE IF NOT EXISTS）

-- 会话历史消息：每条记录为一条 user 或 assistant 消息
CREATE TABLE IF NOT EXISTS chat_message (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    session_id  VARCHAR(64)  NOT NULL,
    seq         INT          NOT NULL,
    role        VARCHAR(16)  NOT NULL,
    content     MEDIUMTEXT   NOT NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_session (session_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对话历史消息';

-- 会话滚动摘要：超出窗口的旧消息由 LLM 压缩为摘要，last_seq 为已被摘要覆盖的最大消息 seq
CREATE TABLE IF NOT EXISTS chat_session_summary (
    session_id  VARCHAR(64)  NOT NULL,
    summary     TEXT         NOT NULL,
    last_seq    INT          NOT NULL DEFAULT 0,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话滚动摘要';

-- 长期经验的可变元数据（向量与不可变内容存于 Milvus，按 exp_id 关联）
CREATE TABLE IF NOT EXISTS experience_meta (
    exp_id        VARCHAR(64) NOT NULL,
    confidence    DOUBLE      NOT NULL DEFAULT 0.6,
    use_count     INT         NOT NULL DEFAULT 0,
    success_count INT         NOT NULL DEFAULT 0,
    tier          VARCHAR(16) NOT NULL DEFAULT 'strong',
    last_used     DATETIME    NULL,
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (exp_id),
    KEY idx_last_used (last_used)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='长期经验生命周期元数据';

-- 弱触发临时经验（带 TTL，定时清理）
CREATE TABLE IF NOT EXISTS experience_temp (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    exp_id      VARCHAR(64)  NOT NULL,
    session_id  VARCHAR(64)  NULL,
    content     MEDIUMTEXT   NOT NULL,
    symptoms    TEXT         NULL,
    environment TEXT         NULL,
    confidence  DOUBLE       NOT NULL DEFAULT 0.4,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expire_at   DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_exp (exp_id),
    KEY idx_expire (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='弱触发临时经验';
