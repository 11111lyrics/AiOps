package org.example.aiops.model;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.ExperienceService.RecalledExperience;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 一键排障白板：约定 OverAllState 中的 key、合并策略，以及整张白板与 JSON 的互转。
 * 节点只通过这里的常量读写状态，段 A 结束后序列化落库，段 B 从库中反序列化续跑。
 */
public final class Blackboard {

    public static final String INCIDENT_ID = "incident_id";
    public static final String PROVIDER = "provider";
    public static final String INCIDENT = "incident";
    public static final String RECALLED_EXPERIENCE = "recalled_experience";
    public static final String EXPERIENCE_BLOCK = "experience_block";
    public static final String RCA_CONCLUSION = "rca_conclusion";
    public static final String REPORT_MARKDOWN = "report_markdown";
    public static final String PLAYBOOK_DECISION = "playbook_decision";
    public static final String HUMAN_SUGGESTION = "human_suggestion";
    public static final String REPLAN_COUNT = "replan_count";
    public static final String REMEDIATION_PLAN = "remediation_plan";
    public static final String RISK_DECISION = "risk_decision";
    public static final String EXECUTION_RESULT = "execution_result";
    public static final String VERIFICATION_RESULT = "verification_result";
    public static final String DISTILL_RESULT = "distill_result";
    public static final String AUDIT_TRAIL = "audit_trail";
    /** 审批人写入，供段 B 的沉淀节点记录 */
    public static final String APPROVED_BY = "approved_by";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final Map<String, Class<?>> TYPED_KEYS = new LinkedHashMap<>();

    static {
        TYPED_KEYS.put(INCIDENT, Incident.class);
        TYPED_KEYS.put(RCA_CONCLUSION, RcaConclusion.class);
        TYPED_KEYS.put(PLAYBOOK_DECISION, PlaybookDecision.class);
        TYPED_KEYS.put(REMEDIATION_PLAN, RemediationPlan.class);
        TYPED_KEYS.put(RISK_DECISION, RiskDecision.class);
        TYPED_KEYS.put(EXECUTION_RESULT, ExecutionResult.class);
        TYPED_KEYS.put(VERIFICATION_RESULT, VerificationResult.class);
        TYPED_KEYS.put(DISTILL_RESULT, DistillResult.class);
    }

    private Blackboard() {
    }

    public static KeyStrategyFactory keyStrategies() {
        return () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();
            for (String key : new String[]{INCIDENT_ID, PROVIDER, INCIDENT, RECALLED_EXPERIENCE, EXPERIENCE_BLOCK,
                    RCA_CONCLUSION, REPORT_MARKDOWN, PLAYBOOK_DECISION, HUMAN_SUGGESTION, REPLAN_COUNT,
                    REMEDIATION_PLAN, RISK_DECISION, EXECUTION_RESULT,
                    VERIFICATION_RESULT, DISTILL_RESULT, APPROVED_BY}) {
                strategies.put(key, new ReplaceStrategy());
            }
            strategies.put(AUDIT_TRAIL, new AppendStrategy());
            return strategies;
        };
    }

    // ==================== 类型化读取 ====================

    public static String incidentId(OverAllState state) {
        return state.value(INCIDENT_ID, String.class).orElse("");
    }

    public static String provider(OverAllState state) {
        return state.value(PROVIDER, String.class).orElse(null);
    }

    public static Optional<Incident> incident(OverAllState state) {
        return typed(state, INCIDENT, Incident.class);
    }

    public static Optional<RcaConclusion> conclusion(OverAllState state) {
        return typed(state, RCA_CONCLUSION, RcaConclusion.class);
    }

    public static String report(OverAllState state) {
        return state.value(REPORT_MARKDOWN, String.class).orElse("");
    }

    public static String experienceBlock(OverAllState state) {
        return state.value(EXPERIENCE_BLOCK, String.class).orElse("");
    }

    public static Optional<PlaybookDecision> playbookDecision(OverAllState state) {
        return typed(state, PLAYBOOK_DECISION, PlaybookDecision.class);
    }

    public static String humanSuggestion(OverAllState state) {
        return state.value(HUMAN_SUGGESTION, String.class).orElse("");
    }

    public static int replanCount(OverAllState state) {
        Object raw = state.value(REPLAN_COUNT).orElse(0);
        if (raw instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (Exception e) {
            return 0;
        }
    }

    public static Optional<RemediationPlan> plan(OverAllState state) {
        return typed(state, REMEDIATION_PLAN, RemediationPlan.class);
    }

    public static Optional<RiskDecision> risk(OverAllState state) {
        return typed(state, RISK_DECISION, RiskDecision.class);
    }

    public static String riskDecisionCode(OverAllState state) {
        return risk(state).map(RiskDecision::getDecision).orElse(RiskDecision.NO_ACTION);
    }

    public static Optional<ExecutionResult> execution(OverAllState state) {
        return typed(state, EXECUTION_RESULT, ExecutionResult.class);
    }

    public static Optional<VerificationResult> verification(OverAllState state) {
        return typed(state, VERIFICATION_RESULT, VerificationResult.class);
    }

    @SuppressWarnings("unchecked")
    public static List<RecalledExperience> recalled(OverAllState state) {
        Object raw = state.value(RECALLED_EXPERIENCE).orElse(null);
        if (raw instanceof List<?> list) {
            if (list.isEmpty() || list.get(0) instanceof RecalledExperience) {
                return (List<RecalledExperience>) list;
            }
            return MAPPER.convertValue(list, new TypeReference<List<RecalledExperience>>() {
            });
        }
        return new ArrayList<>();
    }

    /**
     * 白板值可能是原对象（同一进程内）或反序列化后的 Map（段 B 从库读回），统一转成目标类型。
     */
    private static <T> Optional<T> typed(OverAllState state, String key, Class<T> type) {
        Object raw = state.value(key).orElse(null);
        if (raw == null) {
            return Optional.empty();
        }
        if (type.isInstance(raw)) {
            return Optional.of(type.cast(raw));
        }
        try {
            return Optional.of(MAPPER.convertValue(raw, type));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ==================== 序列化 ====================

    public static String toJson(Map<String, Object> data) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (entry.getKey().startsWith("_")) {
                continue;
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        try {
            return MAPPER.writeValueAsString(copy);
        } catch (Exception e) {
            throw new IllegalStateException("白板序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 反序列化为可直接作为图输入的 Map：已知 key 还原为 POJO，其余保留 JSON 原生类型。
     */
    public static Map<String, Object> fromJson(String json) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            root.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                try {
                    if (TYPED_KEYS.containsKey(key)) {
                        result.put(key, MAPPER.treeToValue(value, TYPED_KEYS.get(key)));
                    } else if (RECALLED_EXPERIENCE.equals(key)) {
                        result.put(key, MAPPER.convertValue(value, new TypeReference<List<RecalledExperience>>() {
                        }));
                    } else if (AUDIT_TRAIL.equals(key)) {
                        result.put(key, MAPPER.convertValue(value, new TypeReference<List<String>>() {
                        }));
                    } else {
                        result.put(key, MAPPER.treeToValue(value, Object.class));
                    }
                } catch (Exception e) {
                    // 单个 key 损坏不阻断整张白板恢复
                }
            });
        } catch (Exception e) {
            throw new IllegalStateException("白板反序列化失败: " + e.getMessage(), e);
        }
        return result;
    }

    public static String toJsonQuietly(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
