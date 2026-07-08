package org.example.service;

import com.google.gson.JsonObject;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.InsertParam;
import org.example.constant.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 情景记忆服务
 * 把每轮问答归档进 Milvus episodic 集合，使超出短期窗口的历史对话可被跨会话语义检索，
 * 支撑"上次那个告警是怎么处理的"这类回溯性提问。
 */
@Service
public class EpisodicMemoryService {

    private static final Logger logger = LoggerFactory.getLogger(EpisodicMemoryService.class);

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 参与向量化的文本最大长度（问题 + 回复截断） */
    private static final int VECTOR_TEXT_MAX = 2000;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Autowired
    private VectorSearchService vectorSearchService;

    @Autowired
    private MilvusServiceClient milvusClient;

    @Value("${memory.episodic.enabled:true}")
    private boolean enabled;

    @Value("${memory.episodic.top-k:3}")
    private int topK;

    @Value("${memory.episodic.min-score:0.5}")
    private double minScore;

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 归档一轮问答到情景记忆。设计为对话完成后异步调用，失败不影响主流程。
     */
    public void archiveTurn(String sessionId, String question, String answer) {
        if (!enabled || question == null || question.isBlank()) {
            return;
        }
        try {
            String time = LocalDateTime.now().format(TIME_FMT);
            String content = buildContent(time, sessionId, question, answer);

            String vectorText = question + "\n" + safeTruncate(answer, VECTOR_TEXT_MAX - question.length());
            List<Float> vector = embeddingService.generateEmbedding(vectorText);

            JsonObject metadata = new JsonObject();
            metadata.addProperty("_type", "episodic");
            metadata.addProperty("session_id", sessionId == null ? "" : sessionId);
            metadata.addProperty("create_time", System.currentTimeMillis());

            String id = UUID.randomUUID().toString();
            insertToMilvus(id, content, vector, metadata);
            logger.info("情景记忆已归档 - session={}, id={}", sessionId, id);
        } catch (Exception e) {
            logger.warn("情景记忆归档失败（不影响主流程）- session={}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 检索历史会话中的相关片段，按相似度过滤后返回；无命中返回空列表。
     */
    public List<EpisodicRecord> search(String query) {
        List<EpisodicRecord> records = new ArrayList<>();
        if (!enabled || query == null || query.isBlank()) {
            return records;
        }
        try {
            List<VectorSearchService.SearchResult> candidates = vectorSearchService.searchSimilarDocuments(
                    query, Math.max(topK * 2, 6), MilvusConstants.EPISODIC_COLLECTION_NAME);
            for (VectorSearchService.SearchResult c : candidates) {
                // L2 距离转余弦相似度（向量近似归一化：cos ≈ 1 - dist/2）
                double cosScore = 1.0 - (c.getScore() / 2.0);
                if (cosScore < minScore) {
                    continue;
                }
                EpisodicRecord record = new EpisodicRecord();
                record.setId(c.getId());
                record.setContent(c.getContent());
                record.setScore(cosScore);
                records.add(record);
                if (records.size() >= topK) {
                    break;
                }
            }
            return records;
        } catch (Exception e) {
            logger.warn("情景记忆检索失败（不影响主流程）: {}", e.getMessage());
            return records;
        }
    }

    /**
     * 拼装归档正文（受 Milvus content 字段长度限制，超长截断回复部分）。
     */
    private String buildContent(String time, String sessionId, String question, String answer) {
        String header = "[时间] " + time + "\n[会话] " + (sessionId == null ? "" : sessionId)
                + "\n[用户问题] " + question + "\n[助手回复] ";
        int remain = MilvusConstants.CONTENT_MAX_LENGTH - header.length();
        return header + safeTruncate(answer, remain);
    }

    private String safeTruncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        if (maxLen <= 0) {
            return "";
        }
        return text.length() > maxLen ? text.substring(0, maxLen) : text;
    }

    private void insertToMilvus(String id, String content, List<Float> vector, JsonObject metadata) {
        R<RpcStatus> loadResponse = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                        .withCollectionName(MilvusConstants.EPISODIC_COLLECTION_NAME)
                        .build());
        if (loadResponse.getStatus() != 0 && loadResponse.getStatus() != 65535) {
            throw new RuntimeException("加载情景记忆集合失败: " + loadResponse.getMessage());
        }

        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("id", Collections.singletonList(id)));
        fields.add(new InsertParam.Field("content", Collections.singletonList(content)));
        fields.add(new InsertParam.Field("vector", Collections.singletonList(vector)));
        fields.add(new InsertParam.Field("metadata", Collections.singletonList(metadata)));

        R<MutationResult> insertResponse = milvusClient.insert(InsertParam.newBuilder()
                .withCollectionName(MilvusConstants.EPISODIC_COLLECTION_NAME)
                .withFields(fields)
                .build());
        if (insertResponse.getStatus() != 0) {
            throw new RuntimeException("插入情景记忆失败: " + insertResponse.getMessage());
        }
    }

    /**
     * 情景记忆检索结果。
     */
    public static class EpisodicRecord {
        private String id;
        private String content;
        private double score;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public double getScore() { return score; }
        public void setScore(double score) { this.score = score; }
    }
}
