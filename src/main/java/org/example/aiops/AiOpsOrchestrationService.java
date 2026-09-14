package org.example.aiops;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.example.aiops.IncidentRepository.IncidentRow;
import org.example.aiops.config.AiOpsOrchestrationProperties;
import org.example.aiops.model.Blackboard;
import org.example.aiops.model.IncidentStatus;
import org.example.aiops.model.RemediationPlan;
import org.example.aiops.model.RiskDecision;
import org.example.config.ChatModelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * 一键排障编排入口：段 A 手动触发，L0/L1 同请求内执行；L2 落库等待
 * {@link #approve} 起段 B。白板跨请求靠 incident.state_json。
 */
@Service
public class AiOpsOrchestrationService {

    private static final Logger logger = LoggerFactory.getLogger(AiOpsOrchestrationService.class);

    private final CompiledGraph phaseA;
    private final CompiledGraph phaseB;
    private final CompiledGraph phaseRevise;
    private final IncidentRepository repository;
    private final IncidentProgressBus bus;
    private final ChatModelFactory chatModelFactory;
    private final AiOpsOrchestrationProperties properties;

    public AiOpsOrchestrationService(@Qualifier("aiOpsPhaseA") CompiledGraph phaseA,
                                     @Qualifier("aiOpsPhaseB") CompiledGraph phaseB,
                                     @Qualifier("aiOpsPhaseRevise") CompiledGraph phaseRevise,
                                     IncidentRepository repository,
                                     IncidentProgressBus bus,
                                     ChatModelFactory chatModelFactory,
                                     AiOpsOrchestrationProperties properties) {
        this.phaseA = phaseA;
        this.phaseB = phaseB;
        this.phaseRevise = phaseRevise;
        this.repository = repository;
        this.bus = bus;
        this.chatModelFactory = chatModelFactory;
        this.properties = properties;
    }

    /**
     * 段 A：接入 → RCA → 规划 → 风险分级；AUTO 则继续执行/验证/沉淀。
     *
     * @return incidentId
     */
    public String start(String provider, BiConsumer<String, String> sink) {
        String incidentId = UUID.randomUUID().toString();
        String resolvedProvider = chatModelFactory.resolveProvider(provider);
        repository.create(incidentId, resolvedProvider);
        bus.register(incidentId, sink);
        try {
            bus.content(incidentId, "正在读取告警并拆解任务…\n");
            Map<String, Object> input = new LinkedHashMap<>();
            input.put(Blackboard.INCIDENT_ID, incidentId);
            input.put(Blackboard.PROVIDER, resolvedProvider);

            Optional<OverAllState> result = phaseA.invoke(input);
            if (result.isEmpty()) {
                repository.markFailed(incidentId, "段 A 编排未返回状态");
                bus.content(incidentId, "编排未获取到有效结果\n");
                emitIncident(incidentId);
                return incidentId;
            }
            OverAllState state = result.get();
            persist(incidentId, state);

            RiskDecision risk = Blackboard.risk(state).orElse(null);
            if (risk != null && RiskDecision.PENDING_APPROVAL.equals(risk.getDecision())) {
                repository.updateStatus(incidentId, IncidentStatus.PENDING_APPROVAL);
                emitApproval(incidentId, state, risk);
                bus.content(incidentId, "已进入人工审批，批准后才会执行自愈命令。\n");
            }
            emitIncident(incidentId);
            return incidentId;
        } catch (Exception e) {
            logger.error("段 A 失败 incident={}", incidentId, e);
            repository.markFailed(incidentId, e.getMessage());
            repository.audit(incidentId, "orchestrator", "error", e.toString());
            throw new IllegalStateException("一键排障失败: " + e.getMessage(), e);
        } finally {
            bus.unregister(incidentId);
        }
    }

    /**
     * 段 B：从白板续跑 execute → verify → distill。
     */
    public void approve(String incidentId, String approver, String comment, BiConsumer<String, String> sink) {
        IncidentRow row = repository.find(incidentId)
                .orElseThrow(() -> new IllegalArgumentException("事件不存在: " + incidentId));
        if (!IncidentStatus.PENDING_APPROVAL.name().equals(row.getStatus())) {
            throw new IllegalStateException("当前状态不是待审批: " + row.getStatus());
        }
        if (row.getStateJson() == null || row.getStateJson().isBlank()) {
            throw new IllegalStateException("白板缺失，无法续跑");
        }
        String who = blankToDash(approver, "operator");
        if (!repository.claimForExecution(incidentId, who, comment)) {
            throw new IllegalStateException("审批抢占失败，事件可能已被处理");
        }

        bus.register(incidentId, sink);
        try {
            bus.content(incidentId, "审批通过（" + who + "），开始执行自愈…\n");
            Map<String, Object> input = Blackboard.fromJson(row.getStateJson());
            input.put(Blackboard.INCIDENT_ID, incidentId);
            input.put(Blackboard.APPROVED_BY, who);
            Optional<OverAllState> result = phaseB.invoke(input);
            if (result.isEmpty()) {
                repository.markFailed(incidentId, "段 B 编排未返回状态");
                bus.content(incidentId, "执行编排未获取到有效结果\n");
            } else {
                persist(incidentId, result.get());
            }
            emitIncident(incidentId);
        } catch (Exception e) {
            logger.error("段 B 失败 incident={}", incidentId, e);
            repository.markFailed(incidentId, e.getMessage());
            repository.audit(incidentId, "orchestrator", "error", e.toString());
            throw new IllegalStateException("执行失败: " + e.getMessage(), e);
        } finally {
            bus.unregister(incidentId);
        }
    }

    /**
     * L2「其他建议」：带上人工建议从决策节点重跑选 playbook / 填参，再进风险门。
     */
    public void revise(String incidentId, String approver, String suggestion, BiConsumer<String, String> sink) {
        if (suggestion == null || suggestion.isBlank()) {
            throw new IllegalArgumentException("其他建议不能为空");
        }
        IncidentRow row = repository.find(incidentId)
                .orElseThrow(() -> new IllegalArgumentException("事件不存在: " + incidentId));
        if (!IncidentStatus.PENDING_APPROVAL.name().equals(row.getStatus())) {
            throw new IllegalStateException("当前状态不是待审批: " + row.getStatus());
        }
        if (row.getStateJson() == null || row.getStateJson().isBlank()) {
            throw new IllegalStateException("白板缺失，无法重选");
        }
        Map<String, Object> input = Blackboard.fromJson(row.getStateJson());
        int used = 0;
        Object rawCount = input.get(Blackboard.REPLAN_COUNT);
        if (rawCount instanceof Number n) {
            used = n.intValue();
        } else if (rawCount != null) {
            try {
                used = Integer.parseInt(String.valueOf(rawCount));
            } catch (Exception ignored) {
                used = 0;
            }
        }
        int max = Math.max(1, properties.getRemediation().getMaxReplans());
        if (used >= max) {
            throw new IllegalStateException("已重选 " + used + " 次，达到上限 " + max);
        }
        String who = blankToDash(approver, "operator");
        if (!repository.claimForRevise(incidentId, who, suggestion.trim())) {
            throw new IllegalStateException("重选抢占失败，事件可能已被处理");
        }

        bus.register(incidentId, sink);
        try {
            bus.content(incidentId, "已收到人工建议（" + who + "），回到选命令前重新决策…\n");
            input.put(Blackboard.INCIDENT_ID, incidentId);
            input.put(Blackboard.HUMAN_SUGGESTION, suggestion.trim());
            input.put(Blackboard.REPLAN_COUNT, used + 1);
            Optional<OverAllState> result = phaseRevise.invoke(input);
            if (result.isEmpty()) {
                repository.markFailed(incidentId, "重选编排未返回状态");
                bus.content(incidentId, "重选未获取到有效结果\n");
            } else {
                OverAllState state = result.get();
                persist(incidentId, state);
                RiskDecision risk = Blackboard.risk(state).orElse(null);
                if (risk != null && RiskDecision.PENDING_APPROVAL.equals(risk.getDecision())) {
                    repository.updateStatus(incidentId, IncidentStatus.PENDING_APPROVAL);
                    emitApproval(incidentId, state, risk);
                    bus.content(incidentId, "已按建议重选命令，仍为 L2，请再次审批。\n");
                }
            }
            emitIncident(incidentId);
        } catch (Exception e) {
            logger.error("重选失败 incident={}", incidentId, e);
            repository.updateStatus(incidentId, IncidentStatus.PENDING_APPROVAL);
            repository.audit(incidentId, "orchestrator", "revise_error", e.toString());
            throw new IllegalStateException("按建议重选失败: " + e.getMessage(), e);
        } finally {
            bus.unregister(incidentId);
        }
    }

    public void reject(String incidentId, String approver, String comment) {
        IncidentRow row = repository.find(incidentId)
                .orElseThrow(() -> new IllegalArgumentException("事件不存在: " + incidentId));
        if (!IncidentStatus.PENDING_APPROVAL.name().equals(row.getStatus())) {
            throw new IllegalStateException("当前状态不是待审批: " + row.getStatus());
        }
        String who = blankToDash(approver, "operator");
        repository.markApproval(incidentId, who, comment, IncidentStatus.REJECTED);
        repository.audit(incidentId, "orchestrator", "rejected", who + ": " + (comment == null ? "" : comment));
    }

    public Optional<IncidentRow> get(String incidentId) {
        return repository.find(incidentId);
    }

    public List<IncidentRow> list(String status, int limit) {
        return repository.list(status, Math.min(Math.max(limit, 1), 100));
    }

    public List<IncidentRepository.AuditRow> audits(String incidentId) {
        return repository.audits(incidentId);
    }

    private void persist(String incidentId, OverAllState state) {
        repository.saveState(incidentId, Blackboard.toJson(state.data()));
        String report = Blackboard.report(state);
        if (report != null && !report.isBlank()) {
            repository.updateReport(incidentId, report);
        }
    }

    private void emitApproval(String incidentId, OverAllState state, RiskDecision risk) {
        RemediationPlan plan = Blackboard.plan(state).orElseGet(RemediationPlan::new);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("incidentId", incidentId);
        payload.put("riskLevel", risk.getLevel());
        payload.put("score", risk.getScore());
        payload.put("reasons", risk.getReasons());
        Map<String, Object> planMap = new LinkedHashMap<>();
        planMap.put("playbookId", plan.getPlaybookId());
        planMap.put("title", plan.getTitle());
        planMap.put("target", plan.getTarget());
        planMap.put("commands", plan.getCommands());
        planMap.put("params", plan.getParams());
        planMap.put("rollback", plan.getRollback());
        planMap.put("expectedEffect", plan.getExpectedEffect());
        planMap.put("dryRun", plan.getDryRun());
        planMap.put("decisionReason", plan.getDecisionReason());
        planMap.put("humanSuggestion", plan.getHumanSuggestion());
        payload.put("plan", planMap);
        bus.json(incidentId, IncidentProgressBus.TYPE_APPROVAL, payload);
    }

    private void emitIncident(String incidentId) {
        repository.find(incidentId).ifPresent(row -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("incidentId", row.getId());
            payload.put("status", row.getStatus());
            payload.put("riskLevel", row.getRiskLevel() == null ? "" : row.getRiskLevel());
            payload.put("decision", row.getDecision() == null ? "" : row.getDecision());
            payload.put("firingNames", row.getFiringNames() == null ? "" : row.getFiringNames());
            bus.json(incidentId, IncidentProgressBus.TYPE_INCIDENT, payload);
        });
    }

    private static String blankToDash(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
