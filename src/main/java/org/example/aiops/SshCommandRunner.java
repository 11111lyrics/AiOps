package org.example.aiops;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import lombok.Data;
import org.example.aiops.config.AiOpsOrchestrationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 通过 SSH 在 tjxt 虚机上执行 playbook 命令。命令只来自配置目录，这里不做任何拼接。
 * remediation.enabled=false 时返回模拟结果（dry-run），不建立连接。
 */
@Component
public class SshCommandRunner {

    private static final Logger logger = LoggerFactory.getLogger(SshCommandRunner.class);

    private final AiOpsOrchestrationProperties properties;

    public SshCommandRunner(AiOpsOrchestrationProperties properties) {
        this.properties = properties;
    }

    public boolean isEnabled() {
        AiOpsOrchestrationProperties.Ssh ssh = properties.getRemediation().getSsh();
        return properties.getRemediation().isEnabled()
                && ssh.getHost() != null && !ssh.getHost().isBlank();
    }

    public String describeTarget() {
        AiOpsOrchestrationProperties.Ssh ssh = properties.getRemediation().getSsh();
        return ssh.getUsername() + "@" + ssh.getHost() + ":" + ssh.getPort();
    }

    /**
     * 执行单条命令。未启用时返回 simulated=true、exitCode=0 的结果。
     */
    public CommandOutput run(String command) {
        CommandOutput out = new CommandOutput();
        out.setCommand(command);
        long start = System.currentTimeMillis();
        if (!isEnabled()) {
            out.setSimulated(true);
            out.setExitCode(0);
            out.setStdout("[dry-run] 未启用 remediation，命令未下发");
            out.setDurationMs(0);
            return out;
        }
        AiOpsOrchestrationProperties.Ssh cfg = properties.getRemediation().getSsh();
        Session session = null;
        ChannelExec channel = null;
        try {
            JSch jsch = new JSch();
            session = jsch.getSession(cfg.getUsername(), cfg.getHost(), cfg.getPort());
            session.setPassword(cfg.getPassword());
            session.setConfig("StrictHostKeyChecking", "no");
            session.setConfig("PreferredAuthentications", "password,keyboard-interactive");
            session.setTimeout(cfg.getCommandTimeoutMs());
            session.connect(cfg.getConnectTimeoutMs());

            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            channel.setInputStream(null);
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            channel.setErrStream(err);
            InputStream stdout = channel.getInputStream();
            channel.connect(cfg.getConnectTimeoutMs());

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            long deadline = System.currentTimeMillis() + cfg.getCommandTimeoutMs();
            while (true) {
                while (stdout.available() > 0) {
                    int n = stdout.read(tmp, 0, tmp.length);
                    if (n < 0) {
                        break;
                    }
                    buf.write(tmp, 0, n);
                }
                if (channel.isClosed()) {
                    if (stdout.available() > 0) {
                        continue;
                    }
                    break;
                }
                if (System.currentTimeMillis() > deadline) {
                    out.setTimedOut(true);
                    break;
                }
                Thread.sleep(100);
            }
            out.setExitCode(out.isTimedOut() ? -2 : channel.getExitStatus());
            out.setStdout(truncate(buf.toString(StandardCharsets.UTF_8), cfg.getMaxOutputChars()));
            out.setStderr(truncate(err.toString(StandardCharsets.UTF_8), cfg.getMaxOutputChars()));
        } catch (Exception e) {
            logger.warn("SSH 执行失败 [{}] {}: {}", describeTarget(), command, e.getMessage());
            out.setExitCode(-1);
            out.setStderr(truncate("ssh error: " + e.getMessage(), cfg.getMaxOutputChars()));
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
            if (session != null) {
                session.disconnect();
            }
        }
        out.setDurationMs(System.currentTimeMillis() - start);
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String trimmed = s.strip();
        if (trimmed.length() <= max) {
            return trimmed;
        }
        return trimmed.substring(0, max) + "\n…(输出已截断，共 " + trimmed.length() + " 字符)";
    }

    @Data
    public static class CommandOutput {
        private String command;
        private int exitCode = -1;
        private String stdout = "";
        private String stderr = "";
        private long durationMs;
        private boolean simulated;
        private boolean timedOut;

        public boolean ok() {
            return exitCode == 0;
        }
    }
}
