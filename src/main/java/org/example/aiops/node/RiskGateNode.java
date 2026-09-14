package org.example.aiops.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.example.aiops.IncidentProgressBus;
import org.example.aiops.IncidentRepository;
import org.example.aiops.SshCommandRunner;
import org.example.aiops.TopologyResolver;
import org.example.aiops.config.AiOpsOrchestrationProperties;
import org.example.aiops.model.Blackboard;
import org.example.aiops.model.RcaConclusion;
import org.example.aiops.model.RemediationPlan;
import org.example.aiops.model.RiskDecision;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 节点 ④：风险分级与审批门。L0/L1 且置信度足够 → 自动执行；L2 → 人工审批；前置检查失败 / 熔断 → 不执行。
 * 确定性代码，决策结果驱动条件边。
 */
@Component
public class RiskGateNode extends AbstractOpsNode {

    public static final String NAME = "risk";

    private final AiOpsOrchestrationProperties properties;
    private final TopologyResolver topology;
    private final SshCommandRunner ssh;

    public RiskGateNode(IncidentProgressBus bus, IncidentRepository repository,
                        AiOpsOrchestrationProperties properties, TopologyResolver topology, SshCommandRunner ssh) {
        super(bus, repository);
        this.properties = properties;
        this.topology = topology;
        this.ssh = ssh;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    protected void run(OverAllState state, Map<String, Object> out) {
        RemediationPlan plan = Blackboard.plan(state).orElseGet(() -> {
            RemediationPlan p = new RemediationPlan();
            p.setEmpty(true);
            p.setReason("缺少处置计划");
            return p;
        });
        RcaConclusion conclusion = Blackboard.conclusion(state).orElseGet(RcaConclusion::new);
        AiOpsOrchestrationProperties.Remediation cfg = properties.getRemediation();

        RiskDecision decision = new RiskDecision();
        boolean blocked = false;

        if (plan.isEmpty()) {
            decision.setLevel("-");
            decision.setDecision(RiskDecision.NO_ACTION);
            decision.getReasons().add("无处置计划：" + plan.getReason());
            finish(state, out, decision, plan);
            return;
        }

        String level = plan.getBaseRisk();
        decision.getReasons().add("playbook " + plan.getPlaybookId() + " 基础等级 " + level);

        double confidence = conclusion.isParsed() ? conclusion.getConfidence() : 0.0;
        if (confidence < cfg.getMinConfidenceForAuto() && !"L2".equals(level)) {
            decision.getReasons().add(String.format("RCA 置信度 %.2f 低于自动执行阈值 %.2f，提升为 L2",
                    confidence, cfg.getMinConfidenceForAuto()));
            level = "L2";
        } else if (!conclusion.isParsed()) {
            decision.getReasons().add("RCA 结构化结论缺失，按 L2 处理");
            level = "L2";
        }

        if (plan.isPreconditionChecked() && !plan.isPreconditionOk()) {
            blocked = true;
            decision.getReasons().add("前置检查未通过（" + plan.getPreconditionOutput() + "），playbook 不适用");
        }

        int recent = repository.countRecentExecutions(plan.getPlaybookId(), plan.getTarget(), 1);
        if (recent >= cfg.getCircuitBreaker().getMaxExecutionsPerHour()) {
            blocked = true;
            decision.setCircuitOpen(true);
            decision.getReasons().add(String.format("熔断：%s@%s 近 1 小时已执行 %d 次（上限 %d），需人工介入",
                    plan.getPlaybookId(), plan.getTarget(), recent, cfg.getCircuitBreaker().getMaxExecutionsPerHour()));
        }

        String criticality = topology.criticality(plan.getComponent());
        decision.setLevel(level);
        decision.setScore(score(level, confidence, criticality));

        if (blocked) {
            decision.setDecision(RiskDecision.NO_ACTION);
        } else if (containsIgnoreCase(cfg.getAutoExecuteLevels(), level)) {
            decision.setDecision(RiskDecision.AUTO_EXECUTE);
            decision.getReasons().add(level + " 在自动执行等级 " + cfg.getAutoExecuteLevels() + " 内");
            if (!ssh.isEnabled()) {
                decision.getReasons().add("remediation 未启用，将以 dry-run 模拟执行");
            }
        } else {
            decision.setDecision(RiskDecision.PENDING_APPROVAL);
            decision.getReasons().add(level + " 不在自动执行等级内，等待人工审批");
        }
        finish(state, out, decision, plan);
    }

    private void finish(OverAllState state, Map<String, Object> out, RiskDecision decision, RemediationPlan plan) {
        out.put(Blackboard.RISK_DECISION, decision);
        repository.updateRisk(Blackboard.incidentId(state), decision.getLevel(), decision.getDecision());
        audit(state, "decision", Blackboard.toJsonQuietly(decision));

        String verb = switch (decision.getDecision()) {
            case RiskDecision.AUTO_EXECUTE -> "自动执行";
            case RiskDecision.PENDING_APPROVAL -> "等待人工审批";
            default -> "不执行";
        };
        String line = "【④ 风险分级】等级 " + decision.getLevel() + "，风险分 " + decision.getScore()
                + " → " + verb + "。依据：" + String.join("；", decision.getReasons());
        progress(state, out, line);
        done(state, verb);
    }

    static int score(String level, double confidence, String criticality) {
        int base = switch (level) {
            case "L0" -> 10;
            case "L1" -> 40;
            default -> 70;
        };
        int conf = (int) Math.round((1.0 - Math.max(0.0, Math.min(1.0, confidence))) * 30);
        int crit = "high".equals(criticality) ? 10 : 0;
        return Math.min(100, base + conf + crit);
    }

    private static boolean containsIgnoreCase(List<String> list, String value) {
        if (list == null) {
            return false;
        }
        for (String s : list) {
            if (s != null && s.trim().toUpperCase(Locale.ROOT).equals(value)) {
                return true;
            }
        }
        return false;
    }
}
