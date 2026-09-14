package org.example.aiops.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.example.aiops.IncidentProgressBus;
import org.example.aiops.IncidentRepository;
import org.example.aiops.model.Blackboard;
import org.example.aiops.model.DistillResult;
import org.example.aiops.model.ExecutionResult;
import org.example.aiops.model.Incident;
import org.example.aiops.model.IncidentStatus;
import org.example.aiops.model.RcaConclusion;
import org.example.aiops.model.RemediationPlan;
import org.example.aiops.model.RiskDecision;
import org.example.aiops.model.VerificationResult;
import org.example.service.ExperienceLifecycleService;
import org.example.service.ExperienceService;
import org.example.service.ExperienceService.RecalledExperience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 节点 ⑦：沉淀。复用经验飞轮：采纳经验回写评分、报告 + 处置记录提炼入库。同时收敛 incident 终态。
 */
@Component
public class ExperienceDistillNode extends AbstractOpsNode {

    public static final String NAME = "distill";

    private static final Logger logger = LoggerFactory.getLogger(ExperienceDistillNode.class);

    private final ExperienceService experienceService;
    private final ExperienceLifecycleService lifecycleService;

    public ExperienceDistillNode(IncidentProgressBus bus, IncidentRepository repository,
                                 ExperienceService experienceService, ExperienceLifecycleService lifecycleService) {
        super(bus, repository);
        this.experienceService = experienceService;
        this.lifecycleService = lifecycleService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    protected void run(OverAllState state, Map<String, Object> out) {
        String incidentId = Blackboard.incidentId(state);
        Incident incident = Blackboard.incident(state).orElseGet(Incident::new);
        RcaConclusion conclusion = Blackboard.conclusion(state).orElseGet(RcaConclusion::new);
        String report = Blackboard.report(state);
        Optional<ExecutionResult> execution = Blackboard.execution(state);
        Optional<VerificationResult> verification = Blackboard.verification(state);
        List<RecalledExperience> recalled = Blackboard.recalled(state);

        IncidentStatus finalStatus = resolveFinalStatus(execution, verification);
        String finalText = report + "\n\n" + buildRemediationSection(state, execution, verification);

        DistillResult result = new DistillResult();
        List<String> firing = incident.getFiringNames() == null ? List.of() : incident.getFiringNames();
        boolean grounded = firing.isEmpty()
                || firing.stream().anyMatch(n -> n != null && !n.isBlank() && report.contains(n));
        result.setGrounded(grounded);

        Set<String> adopted = new LinkedHashSet<>();
        try {
            adopted.addAll(experienceService.selectAdoptedExpIds(finalText, recalled));
            for (String id : conclusion.getAdoptedExpIds()) {
                if (id != null && recalled.stream().anyMatch(r -> id.equals(r.getExpId()))) {
                    adopted.add(id);
                }
            }
        } catch (Exception e) {
            logger.warn("采纳经验识别失败: {}", e.getMessage());
        }
        result.setAdoptedExpIds(new ArrayList<>(adopted));

        progress(state, out, "【⑦ 沉淀】采纳历史经验 " + adopted.size() + " 条，正在回写评分并提炼本次经验…");
        try {
            if (!grounded) {
                logger.warn("报告未覆盖当前 firing 告警 {}，对采纳经验负反馈 adopted={}", firing, adopted);
                applyFeedback(new ArrayList<>(adopted), false);
                result.setNote("报告未覆盖当前 firing 告警，不沉淀");
            } else {
                applyFeedback(new ArrayList<>(adopted), true);
                String stored = experienceService.distillAndStore("aiops-" + incidentId, "自动告警分析任务", finalText, false);
                result.setStoredExpId(stored == null ? "" : stored);
                result.setNote(stored == null ? "提炼未入库（置信度低或非排障内容）" : "已入库");
            }
        } catch (Exception e) {
            logger.warn("经验沉淀失败（不影响主流程）: {}", e.getMessage());
            result.setNote("沉淀异常: " + e.getMessage());
        }

        out.put(Blackboard.DISTILL_RESULT, result);
        repository.updateStatus(incidentId, finalStatus);
        audit(state, "distill", Blackboard.toJsonQuietly(result));

        String line = "【⑦ 沉淀】" + result.getNote()
                + (result.getStoredExpId().isBlank() ? "" : "（expId=" + result.getStoredExpId() + "）")
                + "；事件终态 " + finalStatus;
        progress(state, out, line);
        done(state, finalStatus.name());
    }

    private void applyFeedback(List<String> adopted, boolean success) {
        if (adopted.isEmpty()) {
            return;
        }
        experienceService.markUsed(adopted);
        lifecycleService.feedbackBatch(adopted, success);
    }

    static IncidentStatus resolveFinalStatus(Optional<ExecutionResult> execution, Optional<VerificationResult> verification) {
        if (execution.isEmpty()) {
            return IncidentStatus.NO_ACTION;
        }
        if (!execution.get().isSuccess()) {
            return IncidentStatus.FAILED;
        }
        return verification.map(v -> v.isResolved() ? IncidentStatus.RESOLVED : IncidentStatus.UNVERIFIED)
                .orElse(IncidentStatus.UNVERIFIED);
    }

    private String buildRemediationSection(OverAllState state, Optional<ExecutionResult> execution,
                                           Optional<VerificationResult> verification) {
        RemediationPlan plan = Blackboard.plan(state).orElseGet(RemediationPlan::new);
        RiskDecision risk = Blackboard.risk(state).orElseGet(RiskDecision::new);
        StringBuilder sb = new StringBuilder("## 🤖 自动处置记录\n\n");
        if (plan.isEmpty()) {
            sb.append("- 处置计划：无（").append(plan.getReason()).append("）\n");
        } else {
            sb.append("- 处置计划：").append(plan.getPlaybookId()).append(" — ").append(plan.getTitle())
                    .append("（目标 ").append(plan.getTarget()).append("）\n");
            sb.append("- 命令：").append(String.join(" && ", plan.getCommands())).append('\n');
            if (plan.getParams() != null && !plan.getParams().isEmpty()) {
                sb.append("- 参数：").append(plan.getParams()).append('\n');
            }
            if (plan.getHumanSuggestion() != null && !plan.getHumanSuggestion().isBlank()) {
                sb.append("- 人工建议：").append(plan.getHumanSuggestion()).append('\n');
            }
        }
        sb.append("- 风险分级：").append(risk.getLevel()).append("，决策 ").append(risk.getDecision()).append('\n');
        execution.ifPresent(e -> sb.append("- 执行：").append(e.isSimulated() ? "dry-run 模拟；" : "")
                .append(e.isSuccess() ? "成功" : "失败 " + e.getError()).append('\n'));
        verification.ifPresent(v -> sb.append("- 验证：").append(v.isResolved() ? "通过，告警已 resolved" : "未通过")
                .append("（").append(v.getMethod()).append("，").append(v.getDetail()).append("）\n"));
        String approver = state.value(Blackboard.APPROVED_BY, String.class).orElse("");
        if (!approver.isBlank()) {
            sb.append("- 审批人：").append(approver).append('\n');
        }
        return sb.toString();
    }
}
