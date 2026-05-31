package org.example.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文档重排服务
 * 调用阿里云百炼 qwen3-rerank 模型，对向量召回的候选文档按语义相关性重新排序
 */
@Service
public class DocumentRerankService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentRerankService.class);

    private static final String RERANK_API_URL =
            "https://dashscope.aliyuncs.com/compatible-api/v1/reranks";

    private final RestClient restClient;

    @Value("${dashscope.api.key}")
    private String apiKey;

    @Value("${rag.rerank.model:qwen3-rerank}")
    private String model;

    @Value("${rag.rerank.instruct:Given a web search query, retrieve relevant passages that answer the query.}")
    private String instruct;

    public DocumentRerankService(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    /**
     * 对候选文档进行重排
     *
     * @param query      用户查询
     * @param candidates 向量召回的候选文档（保持原始顺序）
     * @param topN       返回前 N 条
     * @return 重排后的文档列表；API 失败时返回原始顺序的前 topN 条（不降级中断流程）
     */
    public List<VectorSearchService.SearchResult> rerank(
            String query,
            List<VectorSearchService.SearchResult> candidates,
            int topN) {

        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (candidates.size() == 1) {
            return new ArrayList<>(candidates);
        }

        try {
            List<String> documents = candidates.stream()
                    .map(VectorSearchService.SearchResult::getContent)
                    .collect(Collectors.toList());

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", model);
            requestBody.put("query", query);
            requestBody.put("documents", documents);
            requestBody.put("top_n", topN);
            requestBody.put("instruct", instruct);

            logger.debug("调用百炼重排 API, model={}, 候选数={}, topN={}", model, documents.size(), topN);

            RerankResponse response = restClient.post()
                    .uri(RERANK_API_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(RerankResponse.class);

            if (response == null || response.results == null || response.results.isEmpty()) {
                logger.warn("百炼重排 API 返回空结果，回退为向量检索原始顺序");
                return truncate(candidates, topN);
            }

            List<VectorSearchService.SearchResult> reranked = new ArrayList<>();
            for (RerankResultItem item : response.results) {
                if (item.index < 0 || item.index >= candidates.size()) {
                    logger.warn("重排结果 index={} 超出候选范围，跳过", item.index);
                    continue;
                }
                VectorSearchService.SearchResult result = candidates.get(item.index);
                result.setRerankScore(item.relevanceScore);
                reranked.add(result);
            }

            if (reranked.isEmpty()) {
                logger.warn("重排结果解析后为空，回退为向量检索原始顺序");
                return truncate(candidates, topN);
            }

            logger.info("百炼重排完成, 返回 {} 条文档", reranked.size());
            return reranked;

        } catch (RestClientResponseException e) {
            logger.warn("百炼重排 API 调用失败 (HTTP {}): {}，回退为向量检索原始顺序",
                    e.getStatusCode().value(), e.getResponseBodyAsString());
            return truncate(candidates, topN);
        } catch (Exception e) {
            logger.warn("百炼重排异常: {}，回退为向量检索原始顺序", e.getMessage());
            return truncate(candidates, topN);
        }
    }

    private List<VectorSearchService.SearchResult> truncate(
            List<VectorSearchService.SearchResult> results, int topN) {
        if (results.size() <= topN) {
            return new ArrayList<>(results);
        }
        return new ArrayList<>(results.subList(0, topN));
    }

    /**
     * 格式化重排前后的顺序对比，用于调试日志
     */
    public static String formatOrderComparison(
            List<VectorSearchService.SearchResult> before,
            List<VectorSearchService.SearchResult> after) {

        String beforeStr = formatOrder(before, true);
        String afterStr = formatOrder(after, false);
        return String.format("重排前(向量分): [%s] -> 重排后(重排分): [%s]", beforeStr, afterStr);
    }

    private static String formatOrder(List<VectorSearchService.SearchResult> results, boolean vectorScore) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            VectorSearchService.SearchResult r = results.get(i);
            String id = r.getId() != null ? r.getId() : "doc-" + i;
            if (vectorScore) {
                parts.add(String.format("%d:%s(score=%.4f)", i + 1, shortenId(id), r.getScore()));
            } else {
                float rerankScore = r.getRerankScore() != null ? r.getRerankScore() : 0f;
                parts.add(String.format("%d:%s(rerank=%.4f)", i + 1, shortenId(id), rerankScore));
            }
        }
        return String.join(", ", parts);
    }

    private static String shortenId(String id) {
        return id.length() > 12 ? id.substring(0, 12) + "..." : id;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RerankResponse {
        @JsonProperty("results")
        List<RerankResultItem> results;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RerankResultItem {
        @JsonProperty("index")
        int index;

        @JsonProperty("relevance_score")
        float relevanceScore;
    }
}
