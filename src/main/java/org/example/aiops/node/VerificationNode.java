package org.example.aiops.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.example.agent.tool.QueryMetricsTools;
import org.example.aiops.IncidentProgressBus;
import org.example.aiops.IncidentRepository;
import org.example.aiops.SshCommandRunner;
import org.example.aiops.config.AiOpsOrchestrationProperties;
import org.example.aiops.model.Blackboard;
import org.example.aiops.model.ExecutionResult;
import org.example.aiops.model.Incident;
import org.example.aiops.model.IncidentStatus;
import org.example.aiops.model.RemediationPlan;
import org.example.aiops.model.VerificationResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 节点 ⑥：验证。有 firing 告警名就轮询 Prometheus 直到全部 resolved 或超时；否则用 playbook 的 verify-command。
 */
@Component
public class VerificationNode extends AbstractOpsNode {

    public static final String NAME = "verify";

    private final QueryMetricsTools queryMetricsTools;
    private final SshCommandRunner ssh;
    private final AiOpsOrchestrationProperties properties;

    public VerificationNode(IncidentProgressBus bus, IncidentRepository repository,
                            QueryMetricsTools queryMetricsTools, SshCommandRunner ssh,
                            AiOpsOrchestrationProperties properties) {
        super(bus, repository);
        this.queryMetricsTools = queryMetricsTools;
        this.ssh = ssh;
        this.properties = properties;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    protected void run(OverAllState state, Map<String, Object> out) throws InterruptedException {
        String incidentId = Blackboard.incidentId(state);
        Incident incident = Blackboard.incident(state).orElseGet(Incident::new);
        RemediationPlan plan = Blackboard.plan(state).orElseGet(RemediationPlan::new);
        ExecutionResult execution = Blackboard.execution(state).orElseGet(ExecutionResult::new);
        VerificationResult result = new VerificationResult();
        long start = System.currentTimeMillis();

        if (!execution.isSuccess()) {
            result.setMethod("skipped");
            result.setDetail("执行未成功，跳过验证：" + execution.getError());
            finish(state, out, result, start);
            return;
        }
        if (execution.isSimulated()) {
            result.setMethod("skipped");
            result.setDetail("dry-run 未真正执行，跳过验证");
            finish(state, out, result, start);
            return;
        }

        repository.updateStatus(incidentId, IncidentStatus.VERIFYING);
        AiOpsOrchestrationProperties.Verify cfg = properties.getRemediation().getVerify();
        List<String> targets = incident.getFiringNames() == null ? List.of() : incident.getFiringNames();

        if (!targets.isEmpty()) {
            result.setMethod("prometheus");
            progress(state, out, "【⑥ 验证】等待告警 " + String.join(", ", targets) + " 恢复（最多 "
                    + cfg.getTimeoutSeconds() + " 秒，每 " + cfg.getIntervalSeconds() + " 秒查一次 Prometheus）…");
            long deadline = start + cfg.getTimeoutSeconds() * 1000L;
            while (true) {
                result.setPolls(result.getPolls() + 1);
                String snapshot = queryMetricsTools.queryPrometheusAlerts();
                List<String> firingNow = queryMetricsTools.extractFiringNames(snapshot);
                List<String> remaining = new ArrayList<>();
                for (String t : targets) {
                    if (firingNow.stream().anyMatch(f -> f.equalsIgnoreCase(t))) {
                        remaining.add(t);
                    }
                }
                result.setRemainingAlerts(remaining);
                if (remaining.isEmpty()) {
                    result.setResolved(true);
                    result.setDetail("目标告警全部不再 firing");
                    break;
                }
                if (System.currentTimeMillis() >= deadline) {
                    result.setDetail("超时仍 firing：" + String.join(", ", remaining));
                    break;
                }
                Thread.sleep(cfg.getIntervalSeconds() * 1000L);
            }
            if (result.isResolved() && !plan.getVerifyCommand().isBlank()) {
                SshCommandRunner.CommandOutput o = ssh.run(plan.getVerifyCommand());
                result.setDetail(result.getDetail() + "；verify-command → " + o.getStdout().strip());
            }
        } else if (!plan.getVerifyCommand().isBlank()) {
            result.setMethod("command");
            progress(state, out, "【⑥ 验证】无 firing 告警可跟踪，改用 verify-command：" + plan.getVerifyCommand());
            SshCommandRunner.CommandOutput o = ssh.run(plan.getVerifyCommand());
            result.setPolls(1);
            String stdout = o.getStdout() == null ? "" : o.getStdout().strip();
            boolean matched;
            String expect = plan.getVerifyExpect();
            if (expect == null || expect.isBlank()) {
                matched = o.ok();
            } else {
                try {
                    matched = Pattern.compile(expect).matcher(stdout).find();
                } catch (Exception e) {
                    matched = stdout.toLowerCase(Locale.ROOT).contains(expect.toLowerCase(Locale.ROOT));
                }
            }
            result.setResolved(o.ok() && matched);
            result.setDetail("exit=" + o.getExitCode() + " stdout=" + stdout + (matched ? "（匹配预期）" : "（不匹配预期 " + expect + "）"));
        } else {
            result.setMethod("none");
            result.setDetail("既无 firing 告警可跟踪，也无 verify-command，无法自动验证");
        }
        finish(state, out, result, start);
    }

    private void finish(OverAllState state, Map<String, Object> out, VerificationResult result, long start) {
        result.setElapsedSeconds((System.currentTimeMillis() - start) / 1000);
        out.put(Blackboard.VERIFICATION_RESULT, result);
        audit(state, "verification", Blackboard.toJsonQuietly(result));
        String line = "【⑥ 验证】" + (result.isResolved() ? "通过" : "未通过") + "（" + result.getMethod() + "，"
                + result.getElapsedSeconds() + " 秒）：" + result.getDetail();
        progress(state, out, line);
        done(state, result.isResolved() ? "resolved" : "unverified");
    }
}
