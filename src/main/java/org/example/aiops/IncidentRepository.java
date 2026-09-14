package org.example.aiops;

import lombok.Data;
import org.example.aiops.model.IncidentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * incident / incident_audit / incident_action 三张表的读写。
 * incident.state_json 保存整张白板，两段式审批靠它跨请求续跑。
 */
@Repository
public class IncidentRepository {

    private static final Logger logger = LoggerFactory.getLogger(IncidentRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public IncidentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void create(String id, String provider) {
        jdbcTemplate.update(
                "INSERT INTO incident (id, status, provider) VALUES (?, ?, ?)",
                id, IncidentStatus.ANALYZING.name(), provider);
    }

    public void updateStatus(String id, IncidentStatus status) {
        jdbcTemplate.update("UPDATE incident SET status = ? WHERE id = ?", status.name(), id);
    }

    public void updateSummary(String id, String firingNames, String rootComponents) {
        jdbcTemplate.update("UPDATE incident SET firing_names = ?, root_components = ? WHERE id = ?",
                firingNames, rootComponents, id);
    }

    public void updateReport(String id, String reportMd) {
        jdbcTemplate.update("UPDATE incident SET report_md = ? WHERE id = ?", reportMd, id);
    }

    public void updateRisk(String id, String level, String decision) {
        jdbcTemplate.update("UPDATE incident SET risk_level = ?, decision = ? WHERE id = ?", level, decision, id);
    }

    public void saveState(String id, String stateJson) {
        jdbcTemplate.update("UPDATE incident SET state_json = ? WHERE id = ?", stateJson, id);
    }

    public void saveStateAndStatus(String id, String stateJson, IncidentStatus status) {
        jdbcTemplate.update("UPDATE incident SET state_json = ?, status = ? WHERE id = ?",
                stateJson, status.name(), id);
    }

    public void markFailed(String id, String error) {
        jdbcTemplate.update("UPDATE incident SET status = ?, error = ? WHERE id = ?",
                IncidentStatus.FAILED.name(), truncate(error, 2000), id);
    }

    public void markApproval(String id, String approver, String comment, IncidentStatus status) {
        jdbcTemplate.update(
                "UPDATE incident SET approved_by = ?, approve_comment = ?, status = ? WHERE id = ?",
                approver, comment, status.name(), id);
    }

    /**
     * 审批入口的并发保护：只有当前仍是 PENDING_APPROVAL 才允许切到 EXECUTING。
     */
    public boolean claimForExecution(String id, String approver, String comment) {
        int rows = jdbcTemplate.update(
                "UPDATE incident SET approved_by = ?, approve_comment = ?, status = ? " +
                        "WHERE id = ? AND status = ?",
                approver, comment, IncidentStatus.EXECUTING.name(), id, IncidentStatus.PENDING_APPROVAL.name());
        return rows > 0;
    }

    /** L2「其他建议」：仍待审批才允许回到决策节点重选。 */
    public boolean claimForRevise(String id, String approver, String comment) {
        int rows = jdbcTemplate.update(
                "UPDATE incident SET approved_by = ?, approve_comment = ?, status = ? " +
                        "WHERE id = ? AND status = ?",
                approver, comment, IncidentStatus.ANALYZING.name(), id, IncidentStatus.PENDING_APPROVAL.name());
        return rows > 0;
    }

    public Optional<IncidentRow> find(String id) {
        List<IncidentRow> rows = jdbcTemplate.query(
                "SELECT id, status, provider, firing_names, root_components, risk_level, decision, report_md, " +
                        "state_json, error, approved_by, approve_comment, created_at, updated_at " +
                        "FROM incident WHERE id = ?",
                ROW_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<IncidentRow> list(String status, int limit) {
        if (status == null || status.isBlank()) {
            return jdbcTemplate.query(
                    "SELECT id, status, provider, firing_names, root_components, risk_level, decision, NULL AS report_md, " +
                            "NULL AS state_json, error, approved_by, approve_comment, created_at, updated_at " +
                            "FROM incident ORDER BY created_at DESC LIMIT ?",
                    ROW_MAPPER, limit);
        }
        return jdbcTemplate.query(
                "SELECT id, status, provider, firing_names, root_components, risk_level, decision, NULL AS report_md, " +
                        "NULL AS state_json, error, approved_by, approve_comment, created_at, updated_at " +
                        "FROM incident WHERE status = ? ORDER BY created_at DESC LIMIT ?",
                ROW_MAPPER, status, limit);
    }

    // ==================== 审计 / 动作 ====================

    public void audit(String incidentId, String node, String event, String detail) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO incident_audit (incident_id, node, event, detail) VALUES (?, ?, ?, ?)",
                    incidentId, node, event, truncate(detail, 60000));
        } catch (Exception e) {
            logger.warn("写审计失败 incident={} node={} event={}: {}", incidentId, node, event, e.getMessage());
        }
    }

    public List<AuditRow> audits(String incidentId) {
        return jdbcTemplate.query(
                "SELECT node, event, detail, created_at FROM incident_audit WHERE incident_id = ? ORDER BY id ASC",
                (rs, i) -> {
                    AuditRow row = new AuditRow();
                    row.setNode(rs.getString("node"));
                    row.setEvent(rs.getString("event"));
                    row.setDetail(rs.getString("detail"));
                    Timestamp ts = rs.getTimestamp("created_at");
                    row.setCreatedAt(ts == null ? 0L : ts.getTime());
                    return row;
                }, incidentId);
    }

    public void recordAction(String incidentId, String playbookId, String target, String command,
                             Integer exitCode, boolean simulated) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO incident_action (incident_id, playbook_id, target, command, exit_code, simulated) " +
                            "VALUES (?, ?, ?, ?, ?, ?)",
                    incidentId, playbookId, target, truncate(command, 4000), exitCode, simulated ? 1 : 0);
        } catch (Exception e) {
            logger.warn("写自愈动作记录失败 incident={} playbook={}: {}", incidentId, playbookId, e.getMessage());
        }
    }

    /** 熔断计数：同 playbook + 目标近 1 小时内真实执行（非模拟）的次数 */
    public int countRecentExecutions(String playbookId, String target, int hours) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(DISTINCT incident_id) FROM incident_action " +
                            "WHERE playbook_id = ? AND target = ? AND simulated = 0 " +
                            "AND created_at > (NOW() - INTERVAL ? HOUR)",
                    Integer.class, playbookId, target, hours);
            return count == null ? 0 : count;
        } catch (Exception e) {
            logger.warn("熔断计数查询失败 playbook={} target={}: {}", playbookId, target, e.getMessage());
            return 0;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…(截断)";
    }

    private static final RowMapper<IncidentRow> ROW_MAPPER = (rs, i) -> {
        IncidentRow row = new IncidentRow();
        row.setId(rs.getString("id"));
        row.setStatus(rs.getString("status"));
        row.setProvider(rs.getString("provider"));
        row.setFiringNames(rs.getString("firing_names"));
        row.setRootComponents(rs.getString("root_components"));
        row.setRiskLevel(rs.getString("risk_level"));
        row.setDecision(rs.getString("decision"));
        row.setReportMd(rs.getString("report_md"));
        row.setStateJson(rs.getString("state_json"));
        row.setError(rs.getString("error"));
        row.setApprovedBy(rs.getString("approved_by"));
        row.setApproveComment(rs.getString("approve_comment"));
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        row.setCreatedAt(created == null ? 0L : created.getTime());
        row.setUpdatedAt(updated == null ? 0L : updated.getTime());
        return row;
    };

    @Data
    public static class IncidentRow {
        private String id;
        private String status;
        private String provider;
        private String firingNames;
        private String rootComponents;
        private String riskLevel;
        private String decision;
        private String reportMd;
        private String stateJson;
        private String error;
        private String approvedBy;
        private String approveComment;
        private long createdAt;
        private long updatedAt;
    }

    @Data
    public static class AuditRow {
        private String node;
        private String event;
        private String detail;
        private long createdAt;
    }
}
