package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

/**
 * 检索编排服务
 * 统一「Milvus 粗召回 → 百炼重排 → 截断 Top-K」两阶段检索流程
 */
@Service
public class RetrievalService {

    private static final Logger logger = LoggerFactory.getLogger(RetrievalService.class);

    @Autowired
    private VectorSearchService vectorSearchService;

    @Autowired
    private DocumentRerankService documentRerankService;

    @Value("${rag.top-k:3}")
    private int topK;

    @Value("${rag.recall-top-k:10}")
    private int recallTopK;

    @Value("${rag.rerank.enabled:true}")
    private boolean rerankEnabled;

    @PostConstruct
    public void init() {
        logger.info("检索服务初始化: topK={}, recallTopK={}, rerankEnabled={}",
                topK, recallTopK, rerankEnabled);
    }

    /**
     * 执行完整检索流程：粗召回 → 重排（可选）→ 返回最终 Top-K
     *
     * @param query 用户查询
     * @return 按相关性排序的文档列表
     */
    public List<VectorSearchService.SearchResult> retrieve(String query) {
        int recallK = rerankEnabled ? Math.max(recallTopK, topK) : topK;

        logger.info("开始检索, query={}, recallK={}, finalTopK={}, rerank={}",
                query, recallK, topK, rerankEnabled);

        List<VectorSearchService.SearchResult> recalled =
                vectorSearchService.searchSimilarDocuments(query, recallK);

        if (recalled.isEmpty()) {
            logger.info("检索无结果");
            return recalled;
        }

        if (!rerankEnabled || recalled.size() <= 1) {
            return truncate(recalled, topK);
        }

        List<VectorSearchService.SearchResult> beforeRerank = new ArrayList<>(recalled);

        List<VectorSearchService.SearchResult> reranked =
                documentRerankService.rerank(query, recalled, topK);

        logger.info("{}", DocumentRerankService.formatOrderComparison(
                truncate(beforeRerank, topK), reranked));

        return reranked;
    }

    private List<VectorSearchService.SearchResult> truncate(
            List<VectorSearchService.SearchResult> results, int limit) {
        if (results.size() <= limit) {
            return new ArrayList<>(results);
        }
        return new ArrayList<>(results.subList(0, limit));
    }
}
