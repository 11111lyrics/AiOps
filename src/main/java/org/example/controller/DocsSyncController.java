package org.example.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.DocumentParseProperties;
import org.example.service.RetrievalService;
import org.example.service.VectorIndexService;
import org.example.service.VectorSearchService;
import org.example.service.parse.DocumentParseService;
import org.example.service.parse.KnowledgeCollectionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档库同步与解析开关状态。
 */
@RestController
@RequestMapping("/api/docs")
public class DocsSyncController {

    private static final Pattern FILE_NAME_JSON = Pattern.compile("\"_file_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern FILE_NAME_MAP = Pattern.compile("_file_name\\s*[=:]\\s*([^,}\\]]+)");

    private final VectorIndexService vectorIndexService;
    private final DocumentParseService documentParseService;
    private final KnowledgeCollectionService knowledgeCollectionService;
    private final RetrievalService retrievalService;
    private final ObjectMapper objectMapper;

    public DocsSyncController(VectorIndexService vectorIndexService,
                              DocumentParseService documentParseService,
                              KnowledgeCollectionService knowledgeCollectionService,
                              RetrievalService retrievalService,
                              ObjectMapper objectMapper) {
        this.vectorIndexService = vectorIndexService;
        this.documentParseService = documentParseService;
        this.knowledgeCollectionService = knowledgeCollectionService;
        this.retrievalService = retrievalService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> sync(
            @RequestParam(name = "force", defaultValue = "false") boolean force) {
        VectorIndexService.IndexingResult result = vectorIndexService.syncDocsLibrary(force);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", result.isSuccess());
        body.put("directory", result.getDirectoryPath());
        body.put("collection", result.getCollectionName());
        body.put("parser", result.getParser());
        body.put("force", force);
        body.put("diskFiles", result.getTotalFiles());
        body.put("added", result.getSuccessCount());
        body.put("updated", result.getUpdateCount());
        body.put("deleted", result.getDeleteCount());
        body.put("skipped", result.getSkipCount());
        body.put("failed", result.getFailCount());
        body.put("durationMs", result.getDurationMs());
        if (result.getErrorMessage() != null) {
            body.put("error", result.getErrorMessage());
        }
        if (!result.getFailedFiles().isEmpty()) {
            body.put("failedFiles", result.getFailedFiles());
        }
        return ResponseEntity.ok(body);
    }

    @GetMapping("/parse")
    public ResponseEntity<Map<String, Object>> parseStatus() {
        DocumentParseProperties properties = documentParseService.properties();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", properties.isEnabled());
        body.put("engine", properties.currentEngine());
        body.put("collection", knowledgeCollectionService.currentCollection());
        body.put("isolateCollection", properties.isIsolateCollection());
        body.put("fallbackToBaseline", properties.isFallbackToBaseline());
        body.put("timeoutMs", properties.getTimeoutMs());

        Map<String, Object> paddle = new LinkedHashMap<>();
        paddle.put("baseUrl", properties.getPaddle().getBaseUrl());
        paddle.put("path", properties.getPaddle().getPath());
        paddle.put("reachable", documentParseService.isReachable(DocumentParseProperties.ENGINE_PADDLE));
        paddle.put("supports", "pdf");
        body.put("paddle", paddle);

        Map<String, Object> mineru = new LinkedHashMap<>();
        mineru.put("baseUrl", properties.getMineru().getBaseUrl());
        mineru.put("path", properties.getMineru().getPath());
        mineru.put("backend", properties.getMineru().getBackend());
        mineru.put("reachable", documentParseService.isReachable(DocumentParseProperties.ENGINE_MINERU));
        mineru.put("supports", "pdf,docx");
        body.put("mineru", mineru);
        return ResponseEntity.ok(body);
    }

    /**
     * 评测 / 调试用：走与 queryInternalDocs 相同的粗召回 + 重排，同时返回 Recall@10 与 Top3。
     */
    @PostMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestBody Map<String, String> request) {
        String query = request == null ? "" : request.getOrDefault("query", "");
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "query 不能为空"));
        }

        RetrievalService.RetrievalTrace trace = retrievalService.retrieveDetailed(query.trim());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("query", query.trim());
        body.put("collection", knowledgeCollectionService.currentCollection());
        body.put("parser", knowledgeCollectionService.currentEngine());
        body.put("recallTopK", trace.recallTopK);
        body.put("topK", trace.topK);
        body.put("rerankEnabled", trace.rerankEnabled);
        body.put("recall", toHits(trace.recalled));
        body.put("top", toHits(trace.reranked));
        return ResponseEntity.ok(body);
    }

    private List<Map<String, Object>> toHits(List<VectorSearchService.SearchResult> results) {
        List<Map<String, Object>> hits = new ArrayList<>();
        if (results == null) {
            return hits;
        }
        for (int i = 0; i < results.size(); i++) {
            VectorSearchService.SearchResult result = results.get(i);
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("rank", i + 1);
            hit.put("id", result.getId());
            hit.put("fileName", extractFileName(result.getMetadata()));
            hit.put("score", result.getScore());
            hit.put("rerankScore", result.getRerankScore());
            hit.put("content", result.getContent());
            hits.add(hit);
        }
        return hits;
    }

    private String extractFileName(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(metadata);
            JsonNode fileName = node.get("_file_name");
            if (fileName != null && fileName.isTextual()) {
                return fileName.asText();
            }
        } catch (Exception ignored) {
            // Milvus 偶发返回非 JSON 的 toString，下面用正则兜底
        }
        Matcher json = FILE_NAME_JSON.matcher(metadata);
        if (json.find()) {
            return json.group(1);
        }
        Matcher map = FILE_NAME_MAP.matcher(metadata);
        if (map.find()) {
            return map.group(1).trim();
        }
        return "";
    }
}
