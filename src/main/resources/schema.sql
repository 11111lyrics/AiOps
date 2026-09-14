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

-- 一键排障事件：七节点编排的白板快照与状态机（两段式审批跨请求靠此表续跑）
CREATE TABLE IF NOT EXISTS incident (
    id              VARCHAR(64)  NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    provider        VARCHAR(32)  NULL,
    firing_names    TEXT         NULL,
    root_components VARCHAR(255) NULL,
    risk_level      VARCHAR(8)   NULL,
    decision        VARCHAR(32)  NULL,
    report_md       MEDIUMTEXT   NULL,
    state_json      MEDIUMTEXT   NULL,
    error           TEXT         NULL,
    approved_by     VARCHAR(64)  NULL,
    approve_comment TEXT         NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='一键排障事件与白板快照';

-- 一键排障审计：每个节点的关键事件
CREATE TABLE IF NOT EXISTS incident_audit (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    incident_id VARCHAR(64)  NOT NULL,
    node        VARCHAR(32)  NOT NULL,
    event       VARCHAR(64)  NOT NULL,
    detail      MEDIUMTEXT   NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_incident (incident_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='一键排障节点审计';

-- 自愈动作：每条实际（或模拟）执行的 playbook 命令，供熔断计数
CREATE TABLE IF NOT EXISTS incident_action (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    incident_id VARCHAR(64)  NOT NULL,
    playbook_id VARCHAR(64)  NOT NULL,
    target      VARCHAR(128) NULL,
    command     TEXT         NOT NULL,
    exit_code   INT          NULL,
    simulated   TINYINT(1)   NOT NULL DEFAULT 0,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_playbook_time (playbook_id, target, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='自愈 playbook 执行记录';

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

-- 一键排障事件：段 A 结束后把整张白板写入 state_json，L2 审批后再用它起段 B
CREATE TABLE IF NOT EXISTS incident (
    id               VARCHAR(64)  NOT NULL,
    status           VARCHAR(32)  NOT NULL,
    provider         VARCHAR(32)  NULL,
    firing_names     VARCHAR(512) NULL,
    root_components  VARCHAR(256) NULL,
    risk_level       VARCHAR(8)   NULL,
    decision         VARCHAR(32)  NULL,
    report_md        MEDIUMTEXT   NULL,
    state_json       MEDIUMTEXT   NULL,
    error            TEXT         NULL,
    approved_by      VARCHAR(64)  NULL,
    approve_comment  VARCHAR(512) NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='一键排障事件';

CREATE TABLE IF NOT EXISTS incident_audit (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    incident_id VARCHAR(64)  NOT NULL,
    node        VARCHAR(32)  NOT NULL,
    event       VARCHAR(64)  NOT NULL,
    detail      MEDIUMTEXT   NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_incident (incident_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='一键排障节点审计';

CREATE TABLE IF NOT EXISTS incident_action (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    incident_id VARCHAR(64)  NOT NULL,
    playbook_id VARCHAR(64)  NOT NULL,
    target      VARCHAR(128) NULL,
    command     TEXT         NOT NULL,
    exit_code   INT          NULL,
    simulated   TINYINT      NOT NULL DEFAULT 0,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_playbook_target_time (playbook_id, target, created_at),
    KEY idx_incident (incident_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='一键排障自愈动作（熔断计数）';
