package org.example.aiops.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 一键排障七节点编排配置：自愈开关与 SSH、熔断、验证、服务依赖拓扑、playbook 目录。
 */
@Data
@Component
@ConfigurationProperties(prefix = "aiops.orchestration")
public class AiOpsOrchestrationProperties {

    private Rca rca = new Rca();
    private Remediation remediation = new Remediation();
    private Topology topology = new Topology();
    private List<Playbook> playbooks = new ArrayList<>();

    public Playbook findPlaybook(String id) {
        if (id == null) {
            return null;
        }
        String norm = id.trim().toLowerCase(Locale.ROOT);
        for (Playbook p : playbooks) {
            if (p.getId() != null && p.getId().trim().toLowerCase(Locale.ROOT).equals(norm)) {
                return p;
            }
        }
        return null;
    }

    /** 节点 ② 根因分析的引擎与护栏 */
    @Data
    public static class Rca {
        public static final String ENGINE_PLAN_EXECUTE = "plan-execute";
        public static final String ENGINE_REACT = "react";

        /** plan-execute = Planner / Executor / Supervisor 多 Agent；react = 单个 ReactAgent */
        private String engine = ENGINE_PLAN_EXECUTE;
        /** 内层 Supervisor 图的递归上限（一次 Planner 或 Executor 调用算一步） */
        private int recursionLimit = 40;
        /** 整个 RCA 环节的墙钟超时；超时即失败，不让 SSE 空转到断开 */
        private int timeoutSeconds = 480;
        /** 等待 RCA 期间每隔多少秒向 SSE 推一行心跳 */
        private int heartbeatSeconds = 30;
        /** 报告尾部缺少结论 JSON 时，是否再用一次无工具的 LLM 调用从报告中抽取 */
        private boolean extractConclusionWithLlm = true;
        private double temperature = 0.3;
        private int maxTokens = 8000;

        public boolean isPlanExecute() {
            return engine == null || !ENGINE_REACT.equalsIgnoreCase(engine.trim());
        }
    }

    @Data
    public static class Remediation {
        /** false = 仅 dry-run：不连 SSH、不执行、不做前置检查 */
        private boolean enabled = false;
        /** 这些等级自动执行，其余进入人工审批 */
        private List<String> autoExecuteLevels = new ArrayList<>(List.of("L0", "L1"));
        /** RCA 置信度低于该值时强制提升到 L2 */
        private double minConfidenceForAuto = 0.7;
        private Ssh ssh = new Ssh();
        private CircuitBreaker circuitBreaker = new CircuitBreaker();
        private Verify verify = new Verify();
        /** L2「其他建议」回到决策节点的次数上限 */
        private int maxReplans = 3;
    }

    @Data
    public static class Ssh {
        private String host = "";
        private int port = 22;
        private String username = "root";
        private String password = "";
        private int connectTimeoutMs = 10000;
        private int commandTimeoutMs = 60000;
        /** 输出截断上限（字符） */
        private int maxOutputChars = 2000;
    }

    @Data
    public static class CircuitBreaker {
        private int maxExecutionsPerHour = 2;
    }

    @Data
    public static class Verify {
        private int timeoutSeconds = 150;
        private int intervalSeconds = 15;
    }

    @Data
    public static class Topology {
        /** alertname → 组件名 */
        private Map<String, String> alertComponents = new LinkedHashMap<>();
        /** 组件名 → 定义 */
        private Map<String, ComponentDef> components = new LinkedHashMap<>();
    }

    @Data
    public static class ComponentDef {
        /** high / medium / low */
        private String criticality = "medium";
        private List<String> dependsOn = new ArrayList<>();
        /** 告警 label（service / job / instance）里可能出现的别名 */
        private List<String> aliases = new ArrayList<>();
    }

    @Data
    public static class Playbook {
        private String id = "";
        private String title = "";
        private String component = "";
        /** 目标（容器名 / 实例），用于熔断计数与展示 */
        private String target = "";
        /** L0 / L1 / L2 */
        private String risk = "L2";
        private List<String> matchAlerts = new ArrayList<>();
        /** 报告文本命中这些关键词也可选中（用于没有 firing 告警的场景） */
        private List<String> matchKeywords = new ArrayList<>();
        private String precondition = "";
        private String preconditionExpect = "";
        /** 句式固定，参数用 {name}；无占位符则原样执行 */
        private List<String> commands = new ArrayList<>();
        /** 占位符取值范围；choices 非空时决策 LLM 只能从中选 */
        private Map<String, PlaybookParam> params = new LinkedHashMap<>();
        private String verifyCommand = "";
        private String verifyExpect = "";
        private String rollback = "";
        private String expectedEffect = "";
    }

    @Data
    public static class PlaybookParam {
        private String description = "";
        private List<String> choices = new ArrayList<>();
        private String defaultValue = "";
    }
}
