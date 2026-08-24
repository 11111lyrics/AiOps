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
 * 统一「HyDE 改写 → Milvus 粗召回 → 百炼重排 → 截断 Top-K」
 */
@Service
public class RetrievalService {

    private static final Logger logger = LoggerFactory.getLogger(RetrievalService.class);

    @Autowired
    private VectorSearchService vectorSearchService;

    @Autowired
    private DocumentRerankService documentRerankService;

    @Autowired
    private HydeService hydeService;

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
        return retrieveDetailed(query).reranked;
    }

    /**
     * 与 {@link #retrieve(String)} 同一条链路，额外带上粗召回 10，供评测 Hit@3 / Recall@10 / MRR。
     */
    public RetrievalTrace retrieveDetailed(String query) {
        int recallK = rerankEnabled ? Math.max(recallTopK, topK) : topK;

        HydeService.Rewrite hyde = hydeService.rewrite(query);
        logger.info("开始检索, query={}, hyde={}, recallK={}, finalTopK={}, rerank={}",
                query, hyde.applied, recallK, topK, rerankEnabled);

        RetrievalTrace trace = new RetrievalTrace();
        trace.recallTopK = recallK;
        trace.topK = topK;
        trace.rerankEnabled = rerankEnabled;
        trace.hydeApplied = hyde.applied;
        trace.hydeText = hyde.applied ? hyde.retrievalText : "";

        List<VectorSearchService.SearchResult> recalled =
                vectorSearchService.searchSimilarDocuments(hyde.retrievalText, recallK);
        trace.recalled = recalled;

        if (recalled.isEmpty()) {
            logger.info("检索无结果");
            trace.reranked = recalled;
            return trace;
        }

        if (!rerankEnabled || recalled.size() <= 1) {
            trace.reranked = truncate(recalled, topK);
            return trace;
        }

        List<VectorSearchService.SearchResult> beforeRerank = new ArrayList<>(recalled);
        List<VectorSearchService.SearchResult> reranked =
                documentRerankService.rerank(query, recalled, topK);
        logger.info("{}", DocumentRerankService.formatOrderComparison(
                truncate(beforeRerank, topK), reranked));
        trace.reranked = reranked;
        return trace;
    }

    public static class RetrievalTrace {
        public String collection;
        public int recallTopK;
        public int topK;
        public boolean rerankEnabled;
        public boolean hydeApplied;
        public String hydeText = "";
        public List<VectorSearchService.SearchResult> recalled = List.of();
        public List<VectorSearchService.SearchResult> reranked = List.of();
    }

    private List<VectorSearchService.SearchResult> truncate(
            List<VectorSearchService.SearchResult> results, int limit) {
        if (results.size() <= limit) {
            return new ArrayList<>(results);
        }
        return new ArrayList<>(results.subList(0, limit));
    }
}
