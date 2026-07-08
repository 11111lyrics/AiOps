package org.example.service;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.utils.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.grpc.MutationResult;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.InsertParam;
import org.example.agent.tool.QueryMetricsTools;
import org.example.constant.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 经验服务
 * 负责：会话分级提炼为结构化经验、写入 Milvus（强）或 MySQL 临时表（弱）、三层过滤召回。
 * 可变的生命周期元数据（置信度/使用计数）存于 MySQL experience_meta，由 ExperienceLifecycleService 维护。
 */
@Service
public class ExperienceService {

    private static final Logger logger = LoggerFactory.getLogger(ExperienceService.class);

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Autowired
    private VectorSearchService vectorSearchService;

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    private QueryMetricsTools queryMetricsTools;

    @Value("${dashscope.api.key}")
    private String apiKey;

    @Value("${experience.enabled:true}")
    private boolean enabled;

    @Value("${experience.recall.top-k:5}")
    private int recallTopK;

    @Value("${experience.recall.min-score:0.6}")
    private double minScore;

    @Value("${experience.distill.min-confidence:0.6}")
    private double minConfidence;

    @Value("${experience.distill.new-pattern-threshold:0.5}")
    private double newPatternThreshold;

    @Value("${experience.temp.ttl-days:365}")
    private int tempTtlDays;

    @Value("${experience.model:qwen-turbo}")
    private String model;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Gson gson = new Gson();
    private Generation generation;

    @PostConstruct
    public void init() {
        if (apiKey != null && !apiKey.isEmpty()) {
            Constants.apiKey = apiKey;
        }
        this.generation = new Generation();
        logger.info("经验服务初始化完成, enabled={}, model={}, recallTopK={}", enabled, model, recallTopK);
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ==================== 经验沉淀（分级触发） ====================

    /**
     * 提炼并按等级沉淀经验。
     *
     * @param sessionId  会话 ID
     * @param question   最新用户问题
     * @param answer     最新 AI 回复
     * @param manualMark 是否人工标记"有价值"（强制强触发）
     */
    public void distillAndStore(String sessionId, String question, String answer, boolean manualMark) {
        if (!enabled) {
            return;
        }
        try {
            String json = callLlmDistill(question, answer);
            if (json == null || json.isBlank()) {
                logger.info("经验提炼无有效输出，跳过 - session={}", sessionId);
                return;
            }

            JsonNode node = objectMapper.readTree(json);
            double confidence = node.path("confidence").asDouble(0.0);
            String rootCause = node.path("root_cause").asText("");
            boolean resolved = node.path("resolved").asBoolean(false);
            boolean transientIssue = node.path("transient").asBoolean(false);

            // 避免沉淀：低置信 / 无根因
            if (confidence < minConfidence || rootCause.isBlank()) {
                logger.info("经验置信度低或无根因，丢弃 - confidence={}, hasRootCause={}", confidence, !rootCause.isBlank());
                return;
            }

            String vectorText = buildVectorText(node);
            boolean newPattern = isNewPattern(vectorText);

            // 避免沉淀：未解决且非新模式且非人工标记
            if (!manualMark && !resolved && !newPattern) {
                logger.info("问题未解决且非新模式，丢弃经验 - session={}", sessionId);
                return;
            }

            boolean metricConfirmed = verifyMetricRecovery(node);
            String tier = decideTier(manualMark, newPattern, resolved, metricConfirmed);

            String expId = UUID.randomUUID().toString();
            if ("strong".equals(tier)) {
                storeStrong(expId, vectorText, json, node, confidence, tier);
                logger.info("[强触发] 经验已沉淀至 Milvus - expId={}, newPattern={}, metricConfirmed={}",
                        expId, newPattern, metricConfirmed);
            } else {
                storeWeak(expId, sessionId, json, node, confidence);
                logger.info("[弱触发] 经验已存入临时库(TTL {}天) - expId={}", tempTtlDays, expId);
            }
        } catch (Exception e) {
            logger.warn("经验提炼/沉淀失败（不影响主流程）- session={}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 决定经验等级：strong / weak。
     */
    private String decideTier(boolean manualMark, boolean newPattern, boolean resolved, boolean metricConfirmed) {
        if (manualMark || newPattern || (resolved && metricConfirmed)) {
            return "strong";
        }
        return "weak";
    }

    /**
     * 强触发：向量+不可变经验入 Milvus，可变元数据入 MySQL experience_meta。
     */
    private void storeStrong(String expId, String vectorText, String fullJson, JsonNode node,
                             double confidence, String tier) throws Exception {
        List<Float> vector = embeddingService.generateEmbedding(vectorText);
        insertToMilvus(expId, fullJson, vector, node);

        jdbcTemplate.update(
                "INSERT INTO experience_meta (exp_id, confidence, use_count, success_count, tier, created_at) " +
                        "VALUES (?, ?, 0, 0, ?, NOW())",
                expId, confidence, tier);
    }

    /**
     * 弱触发：存入 MySQL 临时表，带 TTL。
     */
    private void storeWeak(String expId, String sessionId, String fullJson, JsonNode node, double confidence) {
        String symptoms = node.path("symptoms").toString();
        String environment = node.path("environment").toString();
        Timestamp expireAt = Timestamp.valueOf(LocalDateTime.now().plusDays(tempTtlDays));
        jdbcTemplate.update(
                "INSERT INTO experience_temp (exp_id, session_id, content, symptoms, environment, confidence, expire_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                expId, sessionId, fullJson, symptoms, environment, confidence, expireAt);
    }

    /**
     * 调用 LLM 把对话提炼为结构化经验 JSON。
     */
    private String callLlmDistill(String question, String answer) throws Exception {
        String sys = """
                你是 SRE 经验提炼器。请把下面这轮"问题-解决"对话提炼为可复用的结构化经验。
                重点提炼"因果与诊断逻辑"，而非死板操作步骤。仅输出一个 JSON 对象，不要任何额外文字或代码块标记。
                JSON 字段如下：
                {
                  "title": "问题概述",
                  "symptoms": ["症状1", "症状2"],
                  "environment": {"service": "服务名", "runtime": "运行时/平台"},
                  "signals": ["关键证据/信号，如 top、GC日志"],
                  "root_cause": "根本原因（若未定位则留空字符串）",
                  "solution": ["修复方案"],
                  "verification": ["验证手段，如指标恢复"],
                  "risk": ["风险点"],
                  "confidence": 0.0,
                  "reusable_scope": ["适用范围，如 Java、K8s"],
                  "resolved": true,
                  "transient": false,
                  "alert_name": "若与某告警相关则填其名称，否则留空"
                }
                说明：confidence 取 0~1；resolved 表示问题是否已闭环解决；transient 表示是否为瞬时异常/误报。
                """;
        String user = "【用户问题】\n" + safe(question) + "\n\n【AI 回复】\n" + safe(answer);

        GenerationParam param = GenerationParam.builder()
                .apiKey(apiKey)
                .model(model)
                .resultFormat("message")
                .messages(List.of(
                        Message.builder().role(Role.SYSTEM.getValue()).content(sys).build(),
                        Message.builder().role(Role.USER.getValue()).content(user).build()))
                .build();

        GenerationResult result = generation.call(param);
        if (result == null || result.getOutput() == null
                || result.getOutput().getChoices() == null
                || result.getOutput().getChoices().isEmpty()) {
            return null;
        }
        String content = result.getOutput().getChoices().get(0).getMessage().getContent();
        return extractJson(content);
    }

    // ==================== 经验复用（三层过滤召回） ====================

    /**
     * 召回相关经验并格式化为提示词注入块；无命中返回空串。
     */
    public String recallForPrompt(String query) {
        if (!enabled) {
            return "";
        }
        return formatExperienceBlock(recall(query, null));
    }

    /**
     * 把召回的经验列表格式化为提示词注入块；空列表返回空串。
     */
    public String formatExperienceBlock(List<RecalledExperience> experiences) {
        if (experiences == null || experiences.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("--- 相关历史经验（仅供参考，必须结合当前证据验证后再采纳，禁止直接照搬执行）---\n");
        int idx = 1;
        for (RecalledExperience exp : experiences) {
            sb.append(String.format("【候选经验%d｜可信度%.2f】%s\n", idx++, exp.getFinalScore(), exp.getContent()));
        }
        sb.append("--- 历史经验结束 ---\n\n");
        return sb.toString();
    }

    /**
     * 三层过滤召回：L1 症状向量粗召回 → L2 环境过滤 → L3 证据/置信度排序。
     *
     * @param query   症状/问题文本
     * @param envHint 环境提示（可空），用于 L2 过滤
     */
    public List<RecalledExperience> recall(String query, String envHint) {
        List<RecalledExperience> result = new ArrayList<>();
        if (!enabled || query == null || query.isBlank()) {
            return result;
        }
        try {
            // L1：症状向量粗召回（多取一些候选用于后续过滤）
            List<VectorSearchService.SearchResult> candidates =
                    vectorSearchService.searchSimilarDocuments(query, Math.max(recallTopK * 2, 10),
                            MilvusConstants.EXPERIENCE_COLLECTION_NAME);

            for (VectorSearchService.SearchResult c : candidates) {
                // L2 距离转余弦相似度（DashScope 向量近似归一化：cos ≈ 1 - dist/2）
                double cosScore = 1.0 - (c.getScore() / 2.0);
                if (cosScore < minScore) {
                    continue;
                }
                // L2：环境匹配过滤
                if (!environmentMatches(c.getContent(), envHint)) {
                    continue;
                }
                // L3：结合置信度的最终排序分
                double confidence = loadConfidence(c.getId());
                RecalledExperience re = new RecalledExperience();
                re.setExpId(c.getId());
                re.setContent(c.getContent());
                re.setSimScore(cosScore);
                re.setConfidence(confidence);
                re.setFinalScore(cosScore * confidence);
                result.add(re);
            }

            result.sort((a, b) -> Double.compare(b.getFinalScore(), a.getFinalScore()));
            List<RecalledExperience> top = result.size() > recallTopK ? result.subList(0, recallTopK) : result;

            // 更新使用统计（命中即记一次使用）
            for (RecalledExperience re : top) {
                touchUsage(re.getExpId());
            }
            return new ArrayList<>(top);
        } catch (Exception e) {
            logger.warn("经验召回失败（不影响主流程）: {}", e.getMessage());
            return result;
        }
    }

    /**
     * L2 环境匹配：envHint 为空则放行；否则要求经验的环境/适用范围与提示存在交集。
     */
    private boolean environmentMatches(String content, String envHint) {
        if (envHint == null || envHint.isBlank()) {
            return true;
        }
        try {
            JsonNode node = objectMapper.readTree(content);
            String scope = (node.path("environment").toString() + node.path("reusable_scope").toString()).toLowerCase();
            String hint = envHint.toLowerCase();
            for (String token : hint.split("[\\s,，、]+")) {
                if (!token.isBlank() && scope.contains(token)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return true; // 解析失败时不因 L2 过滤丢弃
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 与已有经验比对，判断是否为新根因模式（最高相似度低于阈值视为新模式）。
     */
    private boolean isNewPattern(String vectorText) {
        try {
            List<VectorSearchService.SearchResult> existing =
                    vectorSearchService.searchSimilarDocuments(vectorText, 1,
                            MilvusConstants.EXPERIENCE_COLLECTION_NAME);
            if (existing.isEmpty()) {
                return true;
            }
            double cosScore = 1.0 - (existing.get(0).getScore() / 2.0);
            return cosScore < newPatternThreshold;
        } catch (Exception e) {
            // 集合为空或搜索失败时，视为新模式
            return true;
        }
    }

    /**
     * 指标复核：尽力判断告警是否已解除（best-effort，失败返回 false 走弱触发）。
     */
    private boolean verifyMetricRecovery(JsonNode node) {
        if (queryMetricsTools == null) {
            return false;
        }
        try {
            String alertName = node.path("alert_name").asText("");
            String alertsJson = queryMetricsTools.queryPrometheusAlerts();
            if (alertsJson == null) {
                return false;
            }
            // 若经验关联了具体告警，且当前活动告警中已不再包含该告警，则视为已恢复
            if (!alertName.isBlank()) {
                return !alertsJson.contains(alertName);
            }
            // 未关联具体告警：当前无任何活动告警则视为恢复
            JsonNode alerts = objectMapper.readTree(alertsJson).path("alerts");
            return alerts.isArray() && alerts.isEmpty();
        } catch (Exception e) {
            logger.debug("指标复核失败，按未确认处理: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 拼装用于向量化的症状导向文本。
     */
    private String buildVectorText(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append(node.path("title").asText("")).append("\n");
        sb.append("症状: ").append(node.path("symptoms").toString()).append("\n");
        sb.append("根因: ").append(node.path("root_cause").asText(""));
        return sb.toString();
    }

    /**
     * 插入经验到 Milvus 经验集合。
     */
    private void insertToMilvus(String expId, String fullJson, List<Float> vector, JsonNode node) throws Exception {
        R<RpcStatus> loadResponse = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                        .withCollectionName(MilvusConstants.EXPERIENCE_COLLECTION_NAME)
                        .build());
        if (loadResponse.getStatus() != 0 && loadResponse.getStatus() != 65535) {
            throw new RuntimeException("加载经验集合失败: " + loadResponse.getMessage());
        }

        String content = fullJson;
        if (content.length() > MilvusConstants.CONTENT_MAX_LENGTH) {
            content = content.substring(0, MilvusConstants.CONTENT_MAX_LENGTH);
        }

        JsonObject metadata = new JsonObject();
        metadata.addProperty("_type", "experience");
        metadata.addProperty("tier", "strong");
        metadata.addProperty("create_time", System.currentTimeMillis());
        metadata.add("environment", gson.toJsonTree(jsonToString(node.path("environment"))));
        metadata.add("reusable_scope", gson.toJsonTree(jsonToString(node.path("reusable_scope"))));

        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("id", Collections.singletonList(expId)));
        fields.add(new InsertParam.Field("content", Collections.singletonList(content)));
        fields.add(new InsertParam.Field("vector", Collections.singletonList(vector)));
        fields.add(new InsertParam.Field("metadata", Collections.singletonList(metadata)));

        InsertParam insertParam = InsertParam.newBuilder()
                .withCollectionName(MilvusConstants.EXPERIENCE_COLLECTION_NAME)
                .withFields(fields)
                .build();

        R<MutationResult> insertResponse = milvusClient.insert(insertParam);
        if (insertResponse.getStatus() != 0) {
            throw new RuntimeException("插入经验向量失败: " + insertResponse.getMessage());
        }
    }

    /**
     * 读取经验置信度（无元数据时默认 0.6）。
     */
    private double loadConfidence(String expId) {
        try {
            Double c = jdbcTemplate.query(
                    "SELECT confidence FROM experience_meta WHERE exp_id = ?",
                    rs -> rs.next() ? rs.getDouble("confidence") : null,
                    expId);
            return c == null ? 0.6 : c;
        } catch (Exception e) {
            return 0.6;
        }
    }

    /**
     * 命中即记一次使用：use_count+1，刷新 last_used。
     */
    private void touchUsage(String expId) {
        try {
            jdbcTemplate.update(
                    "UPDATE experience_meta SET use_count = use_count + 1, last_used = NOW() WHERE exp_id = ?",
                    expId);
        } catch (Exception e) {
            logger.debug("更新经验使用统计失败 expId={}: {}", expId, e.getMessage());
        }
    }

    private String jsonToString(JsonNode node) {
        return node == null || node.isMissingNode() ? "" : node.toString();
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    /**
     * 从模型输出中提取 JSON（去除可能的 ```json 包裹）。
     */
    private String extractJson(String content) {
        if (content == null) {
            return null;
        }
        String text = content.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }

    // ==================== 数据模型 ====================

    /**
     * 召回的经验。
     */
    public static class RecalledExperience {
        private String expId;
        private String content;
        private double simScore;
        private double confidence;
        private double finalScore;

        public String getExpId() { return expId; }
        public void setExpId(String expId) { this.expId = expId; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public double getSimScore() { return simScore; }
        public void setSimScore(double simScore) { this.simScore = simScore; }
        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }
        public double getFinalScore() { return finalScore; }
        public void setFinalScore(double finalScore) { this.finalScore = finalScore; }
    }
}
