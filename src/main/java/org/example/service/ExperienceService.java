package org.example.service;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.utils.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.grpc.MutationResult;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
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

    /** recency 半衰期（天）：经验距上次使用每过一个半衰期，时间因子衰减一半 */
    @Value("${experience.recall.recency.half-life-days:14}")
    private double recencyHalfLifeDays;

    /** recency 因子下限：避免老经验被时间因子完全压死 */
    @Value("${experience.recall.recency.floor:0.3}")
    private double recencyFloor;

    @Value("${experience.distill.min-confidence:0.6}")
    private double minConfidence;

    @Value("${experience.distill.new-pattern-threshold:0.5}")
    private double newPatternThreshold;

    /** 与已有经验相似度达到该阈值时，合并更新已有经验而非新增 */
    @Value("${experience.distill.update-threshold:0.85}")
    private double updateThreshold;

    /** 弱触发经验的初始置信度折扣系数（在提炼置信度基础上打折，使其召回排序自然靠后） */
    @Value("${experience.weak.initial-factor:0.7}")
    private double weakInitialFactor;

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
            TopMatch top = findTopMatch(vectorText);
            boolean newPattern = top == null || top.cosScore < newPatternThreshold;

            // 高相似 + 已闭环（或人工标记）：合并更新已有经验，而非新增一条近似重复
            if (top != null && top.cosScore >= updateThreshold && (resolved || manualMark)) {
                mergeAndUpdate(top.expId, top.content, json, confidence);
                logger.info("[更新] 与已有经验高度相似(cos={})，已合并更新 - expId={}",
                        String.format("%.2f", top.cosScore), top.expId);
                return;
            }

            // 避免沉淀：未解决且非新模式且非人工标记
            if (!manualMark && !resolved && !newPattern) {
                logger.info("问题未解决且非新模式，丢弃经验 - session={}", sessionId);
                return;
            }

            boolean metricConfirmed = verifyMetricRecovery(node);
            String tier = decideTier(manualMark, newPattern, resolved, metricConfirmed);

            String expId = UUID.randomUUID().toString();
            if ("strong".equals(tier)) {
                storeToLongTerm(expId, vectorText, json, node, confidence, "strong");
                logger.info("[强触发] 经验已沉淀至 Milvus - expId={}, newPattern={}, metricConfirmed={}",
                        expId, newPattern, metricConfirmed);
            } else {
                // 弱触发同样入 Milvus 参与召回，但置信度打折、tier=weak；
                // 评分成功后可晋升为 strong，长期低置信则被衰减归档自动遗忘
                double weakConfidence = confidence * weakInitialFactor;
                storeToLongTerm(expId, vectorText, json, node, weakConfidence, "weak");
                logger.info("[弱触发] 经验已沉淀至 Milvus(tier=weak, confidence={}) - expId={}",
                        String.format("%.2f", weakConfidence), expId);
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
     * 沉淀到长期库：向量+不可变经验入 Milvus（metadata 带 tier），可变元数据入 MySQL experience_meta。
     * strong 与 weak 共用此路径，差别仅在 tier 标签与初始置信度；
     * weak 经验由此获得召回资格（排序自然靠后），并可经评分反馈晋升为 strong。
     */
    private void storeToLongTerm(String expId, String vectorText, String fullJson, JsonNode node,
                                 double confidence, String tier) throws Exception {
        List<Float> vector = embeddingService.generateEmbedding(vectorText);
        insertToMilvus(expId, fullJson, vector, node, tier);

        jdbcTemplate.update(
                "INSERT INTO experience_meta (exp_id, confidence, use_count, success_count, tier, created_at) " +
                        "VALUES (?, ?, 0, 0, ?, NOW())",
                expId, confidence, tier);
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

    // ==================== Agent 主动记忆 ====================

    /**
     * Agent 主动保存一条记忆（saveMemory 工具入口）。
     * 与已有经验高度相似时走合并更新，否则作为新经验直接入长期库。
     *
     * @param title   记忆标题
     * @param content 要记住的内容（结论/规则/排障要点）
     * @return 保存成功返回 expId，失败或功能关闭返回 null
     */
    public String saveAgentMemory(String title, String content) {
        if (!enabled || content == null || content.isBlank()) {
            return null;
        }
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("title", title == null || title.isBlank() ? content.substring(0, Math.min(30, content.length())) : title);
            node.put("note", content);
            node.put("source", "agent");
            node.put("confidence", 0.7);
            node.put("resolved", true);
            String json = objectMapper.writeValueAsString(node);
            String vectorText = node.path("title").asText("") + "\n" + content;

            // 与已有经验高度相似 → 合并更新，避免重复记忆
            TopMatch top = findTopMatch(vectorText);
            if (top != null && top.cosScore >= updateThreshold) {
                mergeAndUpdate(top.expId, top.content, json, 0.7);
                logger.info("[Agent记忆] 与已有经验相似，已合并更新 - expId={}", top.expId);
                return top.expId;
            }

            String expId = UUID.randomUUID().toString();
            storeToLongTerm(expId, vectorText, json, node, 0.7, "strong");
            logger.info("[Agent记忆] 已主动保存 - expId={}, title={}", expId, node.path("title").asText());
            return expId;
        } catch (Exception e) {
            logger.warn("Agent 主动保存记忆失败: {}", e.getMessage());
            return null;
        }
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
            String tierTag = "weak".equals(exp.getTier()) ? "｜待验证" : "";
            sb.append(String.format("【候选经验%d｜可信度%.2f%s】%s\n", idx++, exp.getFinalScore(), tierTag, exp.getContent()));
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
                // L3：相似度 × 置信度 × 时间新近因子（近期用过/沉淀的经验权重更高）
                ExperienceMeta meta = loadMeta(c.getId());
                double recency = recencyFactor(meta.refTime);
                RecalledExperience re = new RecalledExperience();
                re.setExpId(c.getId());
                re.setContent(c.getContent());
                re.setSimScore(cosScore);
                re.setConfidence(meta.confidence);
                re.setTier(meta.tier);
                re.setRecencyFactor(recency);
                re.setFinalScore(cosScore * meta.confidence * recency);
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
     * 查找与给定文本最相似的已有经验（top-1），集合为空或搜索失败返回 null。
     * 同时服务于"新模式判定"（相似度 < newPatternThreshold）与"合并更新判定"（相似度 >= updateThreshold）。
     */
    private TopMatch findTopMatch(String vectorText) {
        try {
            List<VectorSearchService.SearchResult> existing =
                    vectorSearchService.searchSimilarDocuments(vectorText, 1,
                            MilvusConstants.EXPERIENCE_COLLECTION_NAME);
            if (existing.isEmpty()) {
                return null;
            }
            VectorSearchService.SearchResult best = existing.get(0);
            TopMatch match = new TopMatch();
            match.expId = best.getId();
            match.content = best.getContent();
            match.cosScore = 1.0 - (best.getScore() / 2.0);
            return match;
        } catch (Exception e) {
            // 集合为空或搜索失败时，视为无匹配（走新模式路径）
            return null;
        }
    }

    /**
     * 合并更新已有经验：LLM 将旧经验与新提炼结果合并为一份，原地替换 Milvus 中的内容
     * （同 expId 删除后重插，向量随之刷新），生命周期元数据（使用计数等）保留。
     */
    private void mergeAndUpdate(String expId, String oldContent, String newJson, double newConfidence) {
        try {
            String merged = callLlmMerge(oldContent, newJson);
            if (merged == null || merged.isBlank()) {
                logger.warn("经验合并 LLM 输出为空，保留旧经验不更新 - expId={}", expId);
                return;
            }
            JsonNode mergedNode = objectMapper.readTree(merged);
            String vectorText = buildVectorText(mergedNode);
            List<Float> vector = embeddingService.generateEmbedding(vectorText);

            deleteFromMilvus(expId);
            insertToMilvus(expId, merged, vector, mergedNode, loadMeta(expId).tier);

            // 元数据保留使用计数，置信度取新旧较大值并刷新使用时间；无记录则补插
            int rows = jdbcTemplate.update(
                    "UPDATE experience_meta SET confidence = GREATEST(confidence, ?), last_used = NOW() WHERE exp_id = ?",
                    newConfidence, expId);
            if (rows == 0) {
                jdbcTemplate.update(
                        "INSERT INTO experience_meta (exp_id, confidence, use_count, success_count, tier, created_at) " +
                                "VALUES (?, ?, 0, 0, 'strong', NOW())",
                        expId, newConfidence);
            }
        } catch (Exception e) {
            logger.warn("经验合并更新失败（保留旧经验）- expId={}: {}", expId, e.getMessage());
        }
    }

    /**
     * 调用 LLM 合并同一根因模式的新旧两条经验。
     */
    private String callLlmMerge(String oldJson, String newJson) throws Exception {
        String sys = """
                你是 SRE 经验库维护器。下面是同一根因模式的两条结构化经验：一条是库中已有的旧经验，一条是刚提炼的新经验。
                请把两者合并为一条更完整、更准确的经验。仅输出一个 JSON 对象，不要任何额外文字或代码块标记。
                合并规则：
                1. 保持与旧经验相同的 JSON 字段结构（title/symptoms/environment/signals/root_cause/solution/verification/risk/confidence/reusable_scope/resolved/transient/alert_name 等）；
                2. 症状、信号、方案、验证手段取并集并去重，新信息补充进来，过时或被新经验推翻的内容以新经验为准；
                3. root_cause 若两者一致则保留，若新经验更具体则采用新表述；
                4. confidence 取两者中较高值；
                5. 不要编造两条经验中都不存在的内容。
                """;
        String user = "【库中已有经验】\n" + safe(oldJson) + "\n\n【新提炼经验】\n" + safe(newJson);

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
        return extractJson(result.getOutput().getChoices().get(0).getMessage().getContent());
    }

    /**
     * 从 Milvus 经验集合删除指定经验（用于合并更新时的原地替换）。
     */
    private void deleteFromMilvus(String expId) {
        milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(MilvusConstants.EXPERIENCE_COLLECTION_NAME)
                .build());
        String expr = String.format("id == \"%s\"", expId);
        R<MutationResult> resp = milvusClient.delete(DeleteParam.newBuilder()
                .withCollectionName(MilvusConstants.EXPERIENCE_COLLECTION_NAME)
                .withExpr(expr)
                .build());
        if (resp.getStatus() != 0) {
            throw new RuntimeException("从 Milvus 删除旧经验失败: " + resp.getMessage());
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
    private void insertToMilvus(String expId, String fullJson, List<Float> vector, JsonNode node, String tier) throws Exception {
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
        metadata.addProperty("tier", tier);
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
     * 读取经验元数据：置信度 + recency 参考时间（优先 last_used，其次 created_at）。
     * 无记录或查询失败时返回默认值（置信度 0.6、无参考时间即 recency 中性）。
     */
    private ExperienceMeta loadMeta(String expId) {
        ExperienceMeta meta = new ExperienceMeta();
        try {
            jdbcTemplate.query(
                    "SELECT confidence, tier, COALESCE(last_used, created_at) AS ref_time " +
                            "FROM experience_meta WHERE exp_id = ?",
                    rs -> {
                        meta.confidence = rs.getDouble("confidence");
                        meta.tier = rs.getString("tier");
                        meta.refTime = rs.getTimestamp("ref_time");
                    },
                    expId);
        } catch (Exception e) {
            logger.debug("读取经验元数据失败 expId={}: {}", expId, e.getMessage());
        }
        return meta;
    }

    /**
     * 时间新近因子：按半衰期指数衰减，收敛到 floor 下限。
     * factor = floor + (1 - floor) * 0.5^(距上次使用天数 / 半衰期)
     */
    private double recencyFactor(Timestamp refTime) {
        if (refTime == null) {
            return 1.0;
        }
        double days = (System.currentTimeMillis() - refTime.getTime()) / 86_400_000.0;
        if (days <= 0) {
            return 1.0;
        }
        double decay = Math.pow(0.5, days / recencyHalfLifeDays);
        return recencyFloor + (1.0 - recencyFloor) * decay;
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
        private String tier;
        private double recencyFactor;
        private double finalScore;

        public String getExpId() { return expId; }
        public void setExpId(String expId) { this.expId = expId; }
        public String getTier() { return tier; }
        public void setTier(String tier) { this.tier = tier; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public double getSimScore() { return simScore; }
        public void setSimScore(double simScore) { this.simScore = simScore; }
        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }
        public double getRecencyFactor() { return recencyFactor; }
        public void setRecencyFactor(double recencyFactor) { this.recencyFactor = recencyFactor; }
        public double getFinalScore() { return finalScore; }
        public void setFinalScore(double finalScore) { this.finalScore = finalScore; }
    }

    /**
     * 已有经验的 top-1 相似匹配。
     */
    private static class TopMatch {
        String expId;
        String content;
        double cosScore;
    }

    /**
     * 经验元数据（置信度 + tier + recency 参考时间）。
     */
    private static class ExperienceMeta {
        double confidence = 0.6;
        String tier = "strong";
        Timestamp refTime;
    }
}
