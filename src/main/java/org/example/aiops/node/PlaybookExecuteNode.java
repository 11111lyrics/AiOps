package org.example.aiops.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.example.aiops.IncidentProgressBus;
import org.example.aiops.IncidentRepository;
import org.example.aiops.SshCommandRunner;
import org.example.aiops.model.Blackboard;
import org.example.aiops.model.ExecutionResult;
import org.example.aiops.model.IncidentStatus;
import org.example.aiops.model.RemediationPlan;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 节点 ⑤：执行 playbook。逐条 SSH 下发命令，退出码非 0 即停；每条命令写 incident_action 供熔断计数。
 */
@Component
public class PlaybookExecuteNode extends AbstractOpsNode {

    public static final String NAME = "execute";

    private final SshCommandRunner ssh;

    public PlaybookExecuteNode(IncidentProgressBus bus, IncidentRepository repository, SshCommandRunner ssh) {
        super(bus, repository);
        this.ssh = ssh;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    protected void run(OverAllState state, Map<String, Object> out) {
        String incidentId = Blackboard.incidentId(state);
        RemediationPlan plan = Blackboard.plan(state).orElse(null);
        ExecutionResult result = new ExecutionResult();
        result.setStartedAt(System.currentTimeMillis());

        if (plan == null || plan.isEmpty() || plan.getCommands().isEmpty()) {
            result.setSuccess(false);
            result.setError("没有可执行的处置计划");
            result.setFinishedAt(System.currentTimeMillis());
            out.put(Blackboard.EXECUTION_RESULT, result);
            progress(state, out, "【⑤ 执行】" + result.getError());
            done(state, result.getError());
            return;
        }

        repository.updateStatus(incidentId, IncidentStatus.EXECUTING);
        result.setPlaybookId(plan.getPlaybookId());
        result.setTarget(plan.getTarget());
        result.setSimulated(!ssh.isEnabled());

        String approver = state.value(Blackboard.APPROVED_BY, String.class).orElse("");
        progress(state, out, "【⑤ 执行】" + (result.isSimulated() ? "[dry-run] " : "")
                + "在 " + ssh.describeTarget() + " 执行 playbook " + plan.getPlaybookId()
                + (approver.isBlank() ? "" : "（审批人：" + approver + "）"));

        boolean allOk = true;
        for (String command : plan.getCommands()) {
            SshCommandRunner.CommandOutput output = ssh.run(command);
            ExecutionResult.CommandRun run = new ExecutionResult.CommandRun();
            run.setCommand(command);
            run.setExitCode(output.getExitCode());
            run.setStdout(output.getStdout());
            run.setStderr(output.getStderr());
            run.setDurationMs(output.getDurationMs());
            run.setSimulated(output.isSimulated());
            result.getRuns().add(run);
            repository.recordAction(incidentId, plan.getPlaybookId(), plan.getTarget(), command,
                    output.getExitCode(), output.isSimulated());

            String line = "  $ " + command + " → exit " + output.getExitCode() + "（" + output.getDurationMs() + "ms）";
            if (!output.getStdout().isBlank()) {
                line += "\n    " + firstLine(output.getStdout());
            }
            if (!output.ok()) {
                line += "\n    stderr: " + firstLine(output.getStderr());
                allOk = false;
                result.setError("命令失败: " + command + " exit=" + output.getExitCode());
            }
            progress(state, out, line);
            if (!allOk) {
                break;
            }
        }
        result.setSuccess(allOk);
        result.setFinishedAt(System.currentTimeMillis());
        out.put(Blackboard.EXECUTION_RESULT, result);
        audit(state, "execution", Blackboard.toJsonQuietly(result));
        done(state, allOk ? "执行完成" : result.getError());
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        int nl = t.indexOf('\n');
        String first = nl < 0 ? t : t.substring(0, nl);
        return first.length() > 160 ? first.substring(0, 160) + "…" : first;
    }
}
