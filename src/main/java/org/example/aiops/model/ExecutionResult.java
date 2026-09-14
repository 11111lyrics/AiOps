package org.example.aiops.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 节点 ⑤ 执行的产物：逐条命令的退出码与截断后的输出。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExecutionResult {

    private boolean success;
    /** true 表示 remediation.enabled=false，命令未真正下发 */
    private boolean simulated;
    private String playbookId = "";
    private String target = "";
    private String error = "";
    private long startedAt;
    private long finishedAt;
    private List<CommandRun> runs = new ArrayList<>();

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CommandRun {
        private String command = "";
        private int exitCode = -1;
        private String stdout = "";
        private String stderr = "";
        private long durationMs;
        private boolean simulated;
    }
}
