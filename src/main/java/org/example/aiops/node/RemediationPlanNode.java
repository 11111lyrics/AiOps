package org.example.aiops.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.example.aiops.IncidentProgressBus;
import org.example.aiops.IncidentRepository;
import org.example.aiops.SshCommandRunner;
import org.example.aiops.TopologyResolver;
import org.example.aiops.PlaybookTemplates;
import org.example.aiops.config.AiOpsOrchestrationProperties;
import org.example.aiops.config.AiOpsOrchestrationProperties.Playbook;
import org.example.aiops.model.Blackboard;
import org.example.aiops.model.Incident;
import org.example.aiops.model.PlaybookDecision;
import org.example.aiops.model.RcaConclusion;
import org.example.aiops.model.RemediationPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 节点 ③ 规划落地：优先采用决策 LLM 已选的 playbook + 填参结果，失败则查表兜底；再做 SSH 前置检查。
 */
@Component
public class RemediationPlanNode extends AbstractOpsNode {

    public static final String NAME = "plan";

    private static final Logger logger = LoggerFactory.getLogger(RemediationPlanNode.class);

    private final AiOpsOrchestrationProperties properties;
    private final SshCommandRunner ssh;

    public RemediationPlanNode(IncidentProgressBus bus, IncidentRepository repository,
                               AiOpsOrchestrationProperties properties, SshCommandRunner ssh) {
        super(bus, repository);
        this.properties = properties;
        this.ssh = ssh;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    protected void run(OverAllState state, Map<String, Object> out) {
        Incident incident = Blackboard.incident(state).orElseGet(Incident::new);
        RcaConclusion conclusion = Blackboard.conclusion(state).orElseGet(RcaConclusion::new);
        String report = Blackboard.report(state);
        PlaybookDecision llmDecision = Blackboard.playbookDecision(state).orElse(null);

        RemediationPlan plan = new RemediationPlan();
        Selection selection = fromDecision(llmDecision);
        if (selection == null && (llmDecision == null || "fallback".equals(llmDecision.getSelectedBy()))) {
            selection = select(incident, conclusion, report);
        }
        if (selection == null) {
            plan.setEmpty(true);
            plan.setReason(reasonWhenEmpty(llmDecision));
            plan.setHumanSuggestion(Blackboard.humanSuggestion(state));
            plan.setDryRun("无可执行的处置计划，仅输出报告。");
            out.put(Blackboard.REMEDIATION_PLAN, plan);
            progress(state, out, "【③ 处置规划】" + plan.getReason());
            done(state, plan.getReason());
            return;
        }

        Playbook pb = selection.playbook;
        Map<String, String> params;
        try {
            params = PlaybookTemplates.resolveParams(pb,
                    llmDecision != null && !llmDecision.getParams().isEmpty()
                            ? llmDecision.getParams() : Map.of());
        } catch (IllegalArgumentException e) {
            plan.setEmpty(true);
            plan.setReason("参数无法落地：" + e.getMessage());
            out.put(Blackboard.REMEDIATION_PLAN, plan);
            progress(state, out, "【③ 处置规划】" + plan.getReason());
            done(state, plan.getReason());
            return;
        }

        plan.setPlaybookId(pb.getId());
        plan.setTitle(pb.getTitle());
        plan.setComponent(pb.getComponent());
        String filledTarget = PlaybookTemplates.fill(
                pb.getTarget() == null || pb.getTarget().isBlank() ? pb.getComponent() : pb.getTarget(), params);
        plan.setTarget(filledTarget);
        plan.setBaseRisk(normalizeRisk(pb.getRisk()));
        plan.setParams(params);
        plan.setCommands(PlaybookTemplates.fillAll(pb.getCommands(), params));
        plan.setRollback(PlaybookTemplates.fill(pb.getRollback(), params));
        plan.setExpectedEffect(pb.getExpectedEffect());
        plan.setVerifyCommand(PlaybookTemplates.fill(pb.getVerifyCommand(), params));
        plan.setVerifyExpect(PlaybookTemplates.fill(pb.getVerifyExpect(), params));
        plan.setSelectedBy(selection.selectedBy);
        plan.setDecisionReason(llmDecision == null ? "" : llmDecision.getReason());
        plan.setHumanSuggestion(Blackboard.humanSuggestion(state));
        plan.setPreconditionCommand(PlaybookTemplates.fill(
                pb.getPrecondition() == null ? "" : pb.getPrecondition(), params));

        runPrecondition(plan, plan.getPreconditionCommand(), pb.getPreconditionExpect());
        plan.setDryRun(buildDryRun(plan));

        out.put(Blackboard.REMEDIATION_PLAN, plan);
        audit(state, "plan", Blackboard.toJsonQuietly(plan));

        StringBuilder line = new StringBuilder("【③ 处置规划】选中 playbook ")
                .append(pb.getId()).append("（").append(pb.getTitle()).append("，来源：").append(selection.selectedBy).append("）");
        if (!plan.isPreconditionChecked()) {
            line.append("；前置检查未执行（remediation 未启用，dry-run）");
        } else if (plan.isPreconditionOk()) {
            line.append("；前置检查通过：").append(abbreviate(plan.getPreconditionOutput(), 60));
        } else {
            line.append("；前置检查未通过：").append(abbreviate(plan.getPreconditionOutput(), 60));
        }
        progress(state, out, line.toString());
        done(state, line.toString());
    }

    private record Selection(Playbook playbook, String selectedBy) {
    }

    private Selection fromDecision(PlaybookDecision decision) {
        if (decision == null || decision.isEmpty() || decision.getPlaybookId().isBlank()) {
            return null;
        }
        Playbook playbook = properties.findPlaybook(decision.getPlaybookId());
        if (playbook == null) {
            return null;
        }
        return new Selection(playbook, decision.getSelectedBy().isBlank() ? "llm" : decision.getSelectedBy());
    }

    private String reasonWhenEmpty(PlaybookDecision decision) {
        if (decision != null && decision.getReason() != null && !decision.getReason().isBlank()) {
            return decision.getReason();
        }
        return properties.getPlaybooks().isEmpty()
                ? "playbook 目录为空"
                : "没有与当前告警 / 根因匹配的 playbook（决策未选中、RCA 未提议、告警名与关键词均未命中）";
    }

    /**
     * 候选顺序：RCA 提议（须在目录中且组件与根候选一致）→ 告警名命中 → 报告文本命中关键词。
     */
    private Selection select(Incident incident, RcaConclusion conclusion, String report) {
        List<String> roots = incident.getRootComponents() == null ? List.of() : incident.getRootComponents();
        for (RcaConclusion.ProposedPlaybook proposed : conclusion.getProposedPlaybooks()) {
            Playbook pb = properties.findPlaybook(proposed.getPlaybookId());
            if (pb == null) {
                logger.info("RCA 提议的 playbook 不在目录中，忽略: {}", proposed.getPlaybookId());
                continue;
            }
            String comp = TopologyResolver.normalize(pb.getComponent());
            boolean componentOk = comp.isEmpty() || roots.isEmpty() || roots.contains(comp)
                    || comp.equals(TopologyResolver.normalize(conclusion.getRootComponent()));
            if (componentOk) {
                return new Selection(pb, "rca_proposed");
            }
            logger.info("RCA 提议的 playbook {} 组件 {} 与根候选 {} 不一致，忽略", pb.getId(), comp, roots);
        }

        List<String> firing = incident.getFiringNames() == null ? List.of() : incident.getFiringNames();
        for (Playbook pb : properties.getPlaybooks()) {
            for (String alertName : pb.getMatchAlerts()) {
                if (firing.stream().anyMatch(f -> f.equalsIgnoreCase(alertName))) {
                    String comp = TopologyResolver.normalize(pb.getComponent());
                    if (comp.isEmpty() || roots.isEmpty() || roots.contains(comp)) {
                        return new Selection(pb, "match_alert");
                    }
                }
            }
        }

        String haystack = ((report == null ? "" : report) + "\n" + conclusion.getRootCause()).toLowerCase(Locale.ROOT);
        for (Playbook pb : properties.getPlaybooks()) {
            for (String keyword : pb.getMatchKeywords()) {
                if (keyword != null && !keyword.isBlank() && haystack.contains(keyword.toLowerCase(Locale.ROOT))) {
                    return new Selection(pb, "match_keyword");
                }
            }
        }
        return null;
    }

    private void runPrecondition(RemediationPlan plan, String command, String expect) {
        if (command == null || command.isBlank()) {
            plan.setPreconditionChecked(false);
            plan.setPreconditionOk(true);
            plan.setPreconditionOutput("（该 playbook 无前置检查）");
            return;
        }
        if (!ssh.isEnabled()) {
            plan.setPreconditionChecked(false);
            plan.setPreconditionOk(true);
            plan.setPreconditionOutput("[dry-run] 未启用 remediation，跳过前置检查");
            return;
        }
        SshCommandRunner.CommandOutput result = ssh.run(command);
        plan.setPreconditionChecked(true);
        String stdout = result.getStdout() == null ? "" : result.getStdout().strip();
        String output = result.ok() ? stdout : ("exit=" + result.getExitCode() + " " + stdout + " " + result.getStderr()).strip();
        plan.setPreconditionOutput(output);
        if (!result.ok()) {
            plan.setPreconditionOk(false);
            return;
        }
        if (expect == null || expect.isBlank()) {
            plan.setPreconditionOk(true);
            return;
        }
        try {
            plan.setPreconditionOk(Pattern.compile(expect).matcher(stdout).find());
        } catch (Exception e) {
            plan.setPreconditionOk(stdout.contains(expect));
        }
    }

    private String buildDryRun(RemediationPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("目标：").append(ssh.describeTarget()).append(" 上的 ").append(plan.getTarget()).append('\n');
        sb.append("将执行：\n");
        for (String c : plan.getCommands()) {
            sb.append("  $ ").append(c).append('\n');
        }
        sb.append("前置检查：").append(plan.getPreconditionCommand().isBlank() ? "无" : plan.getPreconditionCommand())
                .append(" → ").append(plan.getPreconditionOutput()).append('\n');
        sb.append("预期效果：").append(plan.getExpectedEffect()).append('\n');
        sb.append("回滚：").append(plan.getRollback().isBlank() ? "无" : plan.getRollback()).append('\n');
        if (!ssh.isEnabled()) {
            sb.append("注意：remediation 未启用，执行阶段只会模拟。\n");
        }
        return sb.toString();
    }

    private static String normalizeRisk(String risk) {
        String r = risk == null ? "" : risk.trim().toUpperCase(Locale.ROOT);
        return r.equals("L0") || r.equals("L1") ? r : "L2";
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }
}
