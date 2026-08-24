package org.example.service;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.QueryResults;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.response.QueryResultsWrapper;
import lombok.Getter;
import lombok.Setter;
import org.example.config.DocumentParseProperties;
import org.example.dto.DocumentChunk;
import org.example.service.document.DocumentProcessorRegistry;
import org.example.service.parse.DocumentParseService;
import org.example.service.parse.KnowledgeCollectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 向量索引服务。知识库以 aiops-docs 目录为准：扫描新增、删除、更新并同步到 Milvus。
 */
@Service
public class VectorIndexService {

    private static final Logger logger = LoggerFactory.getLogger(VectorIndexService.class);
    private static final Gson GSON = new Gson();
    private static final long QUERY_PAGE_SIZE = 1000L;

    private final Object syncLock = new Object();

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Autowired
    private DocumentChunkService chunkService;

    @Autowired
    private DocumentProcessorRegistry processorRegistry;

    @Autowired
    private DocumentParseService documentParseService;

    @Autowired
    private KnowledgeCollectionService knowledgeCollectionService;

    @Value("${aiops.docs.path:./aiops-docs}")
    private String docsPath;

    /**
     * 以文档目录为唯一来源，对 Milvus 做增量同步：新增入库、删除去向量、内容变化则重建。
     */
    public IndexingResult syncDirectory(String directoryPath) {
        return syncDirectory(directoryPath, false);
    }

    public IndexingResult syncDirectory(String directoryPath, boolean force) {
        synchronized (syncLock) {
            return doSyncDirectory(directoryPath, force);
        }
    }

    /**
     * 兼容旧调用，行为与 {@link #syncDirectory} 相同。
     */
    public IndexingResult indexDirectory(String directoryPath) {
        return syncDirectory(directoryPath);
    }

    public IndexingResult syncDocsLibrary() {
        return syncDocsLibrary(false);
    }

    public IndexingResult syncDocsLibrary(boolean force) {
        return syncDirectory(docsPath, force);
    }

    private IndexingResult doSyncDirectory(String directoryPath, boolean force) {
        IndexingResult result = new IndexingResult();
        result.setStartTime(LocalDateTime.now());

        try {
            String targetPath = (directoryPath != null && !directoryPath.trim().isEmpty())
                    ? directoryPath : docsPath;
            Path docsRoot = Paths.get(targetPath).toAbsolutePath().normalize();
            File directory = docsRoot.toFile();

            if (!directory.exists() || !directory.isDirectory()) {
                throw new IllegalArgumentException("目录不存在或不是有效目录: " + targetPath);
            }

            result.setDirectoryPath(docsRoot.toString());
            knowledgeCollectionService.ensureCurrent();
            result.setCollectionName(currentCollection());
            result.setParser(knowledgeCollectionService.currentEngine());

            List<Path> diskFiles = listDiskFiles(docsRoot);
            result.setTotalFiles(diskFiles.size());
            logger.info("开始同步文档库: {}, collection={}, parser={}, 磁盘文件={}, force={}",
                    docsRoot, currentCollection(), knowledgeCollectionService.currentEngine(),
                    diskFiles.size(), force);

            Map<String, IndexedDoc> indexed = listIndexedDocs(docsRoot);
            Map<Path, IndexedDoc> matched = new LinkedHashMap<>();

            for (Path file : diskFiles) {
                try {
                    IndexedDoc existing = findIndexed(file, docsRoot, indexed);
                    if (existing == null) {
                        indexSingleFile(file.toString());
                        result.incrementSuccessCount();
                        logger.info("新增入库: {}", file.getFileName());
                    } else if (force || needsUpdate(file, existing)) {
                        deleteExistingData(existing.source);
                        indexSingleFile(file.toString());
                        result.incrementUpdateCount();
                        logger.info("检测到更新，已重建向量: {}", file.getFileName());
                    } else {
                        result.incrementSkipCount();
                        logger.debug("未变化，跳过: {}", file.getFileName());
                    }
                    if (existing != null) {
                        matched.put(file, existing);
                    }
                } catch (Exception e) {
                    result.incrementFailCount();
                    result.addFailedFile(file.toString(), e.getMessage());
                    logger.error("同步文件失败: {}", file.getFileName(), e);
                } catch (LinkageError e) {
                    result.incrementFailCount();
                    result.addFailedFile(file.toString(), e.getMessage());
                    logger.error("同步文件失败: {}", file.getFileName(), e);
                }
            }

            for (IndexedDoc leftover : unmatchedIndexed(indexed, matched)) {
                try {
                    deleteExistingData(leftover.source);
                    result.incrementDeleteCount();
                    logger.info("磁盘已删除，已移除向量: {} ({})", leftover.fileName, leftover.source);
                } catch (Exception e) {
                    result.incrementFailCount();
                    result.addFailedFile(leftover.source, e.getMessage());
                    logger.error("删除过期向量失败: {}", leftover.source, e);
                }
            }

            result.setSuccess(result.getFailCount() == 0);
            result.setEndTime(LocalDateTime.now());
            logger.info("文档库同步完成: 磁盘={}, 新增={}, 更新={}, 删除={}, 跳过={}, 失败={}",
                    result.getTotalFiles(), result.getSuccessCount(), result.getUpdateCount(),
                    result.getDeleteCount(), result.getSkipCount(), result.getFailCount());
            return result;
        } catch (Exception e) {
            logger.error("同步文档目录失败", e);
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
            result.setEndTime(LocalDateTime.now());
            return result;
        }
    }

    public void indexSingleFile(String filePath) throws Exception {
        Path path = Paths.get(filePath).toAbsolutePath().normalize();
        File file = path.toFile();

        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("文件不存在: " + filePath);
        }

        knowledgeCollectionService.ensureCurrent();
        logger.info("开始索引文件: {}, collection={}, parser={}",
                path, currentCollection(), knowledgeCollectionService.currentEngine());

        if (!processorRegistry.isSupportedFile(path.toString())) {
            throw new IllegalArgumentException("不支持的文件类型: " + filePath);
        }

        deleteExistingData(normalizeSource(path));

        List<DocumentChunk> chunks = chunkService.chunkDocument(path);
        logger.info("文档分片完成: {} -> {} 个分片", filePath, chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            try {
                List<Float> vector = embeddingService.generateEmbedding(chunk.getContent());
                Map<String, Object> metadata = buildMetadata(path, chunk, chunks.size());
                insertToMilvus(chunk.getContent(), vector, metadata, chunk.getChunkIndex());
                logger.info("✓ 分片 {}/{} 索引成功", i + 1, chunks.size());
            } catch (Exception e) {
                logger.error("✗ 分片 {}/{} 索引失败", i + 1, chunks.size(), e);
                throw new RuntimeException("分片索引失败: " + e.getMessage(), e);
            }
        }

        logger.info("文件索引完成: {}, 共 {} 个分片", filePath, chunks.size());
    }

    private List<Path> listDiskFiles(Path docsRoot) throws IOException {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(docsRoot)) {
            return files;
        }
        try (var stream = Files.walk(docsRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> processorRegistry.isSupportedFile(path.getFileName().toString()))
                    .filter(path -> !isHiddenOrSkipped(docsRoot, path))
                    .sorted()
                    .forEach(files::add);
        }
        return files;
    }

    private boolean isHiddenOrSkipped(Path docsRoot, Path file) {
        Path relative = docsRoot.relativize(file);
        for (Path part : relative) {
            String name = part.toString();
            if (name.startsWith(".") || "chat-attachments".equals(name)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, IndexedDoc> listIndexedDocs(Path docsRoot) {
        Map<String, IndexedDoc> docs = new LinkedHashMap<>();
        try {
            ensureCollectionLoaded();
            long offset = 0;
            while (true) {
                QueryParam queryParam = QueryParam.newBuilder()
                        .withCollectionName(currentCollection())
                        .withExpr("id != \"\"")
                        .withOutFields(List.of("id", "metadata"))
                        .withOffset(offset)
                        .withLimit(QUERY_PAGE_SIZE)
                        .build();
                R<QueryResults> response = milvusClient.query(queryParam);
                if (response.getStatus() != 0) {
                    logger.warn("查询已入库文档失败: {}", response.getMessage());
                    break;
                }
                QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
                List<QueryResultsWrapper.RowRecord> rows = wrapper.getRowRecords();
                if (rows == null || rows.isEmpty()) {
                    break;
                }
                for (QueryResultsWrapper.RowRecord row : rows) {
                    Map<String, Object> metadata = readMetadata(row.get("metadata"));
                    String source = stringValue(metadata.get("_source"));
                    if (!belongsToLibrary(source, docsRoot)) {
                        continue;
                    }
                    IndexedDoc doc = docs.computeIfAbsent(source, key -> new IndexedDoc());
                    doc.source = source;
                    doc.fileName = stringValue(metadata.get("_file_name"));
                    doc.mtime = longValue(metadata.get("_mtime"));
                    doc.size = longValue(metadata.get("_size"));
                    doc.parser = stringValue(metadata.get(DocumentChunkService.META_PARSER));
                    doc.fallback = booleanValue(metadata.get(DocumentChunkService.META_PARSER_FALLBACK));
                    doc.unsupported = booleanValue(metadata.get(DocumentChunkService.META_PARSER_UNSUPPORTED));
                }
                if (rows.size() < QUERY_PAGE_SIZE) {
                    break;
                }
                offset += rows.size();
            }
        } catch (Exception e) {
            logger.warn("列出已入库文档失败，将按新增处理: {}", e.getMessage());
        }
        return docs;
    }

    private IndexedDoc findIndexed(Path file, Path docsRoot, Map<String, IndexedDoc> indexed) {
        String abs = normalizeSource(file);
        if (indexed.containsKey(abs)) {
            return indexed.get(abs);
        }
        String rel = docsRoot.relativize(file.toAbsolutePath().normalize()).toString().replace(File.separator, "/");
        for (IndexedDoc doc : indexed.values()) {
            String source = doc.source.replace("\\", "/");
            if (source.equals(rel)
                    || source.equals("./" + rel)
                    || source.endsWith("/" + rel)) {
                return doc;
            }
        }
        return null;
    }

    private List<IndexedDoc> unmatchedIndexed(Map<String, IndexedDoc> indexed, Map<Path, IndexedDoc> matched) {
        List<IndexedDoc> leftovers = new ArrayList<>();
        for (IndexedDoc doc : indexed.values()) {
            boolean used = false;
            for (IndexedDoc hit : matched.values()) {
                if (hit == doc || hit.source.equals(doc.source)) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                leftovers.add(doc);
            }
        }
        return leftovers;
    }

    private boolean belongsToLibrary(String source, Path docsRoot) {
        if (source == null || source.isBlank()) {
            return false;
        }
        String normalized = source.replace("\\", "/");
        String root = normalizeSource(docsRoot);
        if (normalized.equals(root) || normalized.startsWith(root + "/")) {
            return true;
        }
        String folder = docsRoot.getFileName() != null ? docsRoot.getFileName().toString() : "";
        return !folder.isEmpty()
                && (normalized.startsWith(folder + "/") || normalized.startsWith("./" + folder + "/"));
    }

    private boolean needsUpdate(Path file, IndexedDoc indexed) {
        String expectedParser = documentParseService.expectedParserId(file);
        String indexedParser = indexed.parser == null || indexed.parser.isBlank()
                ? DocumentParseProperties.ENGINE_BASELINE
                : indexed.parser;
        if (!expectedParser.equals(indexedParser)) {
            boolean waitingForParser = indexed.fallback
                    && !DocumentParseProperties.ENGINE_BASELINE.equals(expectedParser)
                    && !documentParseService.isReachableCached(expectedParser);
            if (waitingForParser) {
                logger.debug("解析服务未就绪，暂不重试: {} expected={}", file.getFileName(), expectedParser);
            } else {
                logger.info("解析器变化，将重建: {} indexed={} expected={}",
                        file.getFileName(), indexedParser, expectedParser);
                return true;
            }
        }
        try {
            long size = Files.size(file);
            long mtime = Files.getLastModifiedTime(file).toMillis();
            if (indexed.size < 0 || indexed.mtime < 0) {
                return true;
            }
            return indexed.size != size || indexed.mtime != mtime;
        } catch (IOException e) {
            logger.warn("读取文件指纹失败，将重建: {} - {}", file, e.getMessage());
            return true;
        }
    }

    private void deleteExistingData(String filePath) {
        try {
            String normalizedPath = filePath.replace("\\", "/");
            if (normalizedPath.isBlank()) {
                return;
            }
            String expr = String.format("metadata[\"_source\"] == \"%s\"", escapeExprValue(normalizedPath));
            logger.info("准备删除旧数据，路径: {}, 表达式: {}", normalizedPath, expr);

            ensureCollectionLoaded();

            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .withCollectionName(currentCollection())
                    .withExpr(expr)
                    .build();

            R<MutationResult> response = milvusClient.delete(deleteParam);
            if (response.getStatus() != 0) {
                logger.warn("删除旧数据时出现警告: {}", response.getMessage());
            } else {
                long deletedCount = response.getData().getDeleteCnt();
                logger.info("✓ 已删除文件的旧数据: {}, 删除记录数: {}", normalizedPath, deletedCount);
            }
        } catch (Exception e) {
            logger.warn("删除旧数据失败（可能是首次索引）: {}", e.getMessage());
        }
    }

    private Map<String, Object> buildMetadata(Path path, DocumentChunk chunk, int totalChunks) throws IOException {
        Map<String, Object> metadata = new HashMap<>();
        String normalizedPath = normalizeSource(path);
        Path fileName = path.getFileName();
        String fileNameStr = fileName != null ? fileName.toString() : "";
        String extension = "";
        int dotIndex = fileNameStr.lastIndexOf('.');
        if (dotIndex > 0) {
            extension = fileNameStr.substring(dotIndex);
        }

        metadata.put("_source", normalizedPath);
        metadata.put("_extension", extension);
        metadata.put("_file_name", fileNameStr);
        metadata.put("_mtime", Files.getLastModifiedTime(path).toMillis());
        metadata.put("_size", Files.size(path));
        metadata.put("chunkIndex", chunk.getChunkIndex());
        metadata.put("totalChunks", totalChunks);

        if (chunk.getTitle() != null && !chunk.getTitle().isEmpty()) {
            metadata.put("title", chunk.getTitle());
        }
        if (chunk.getExtraMetadata() != null) {
            chunk.getExtraMetadata().forEach(metadata::put);
        }
        return metadata;
    }

    private void insertToMilvus(String content, List<Float> vector,
                                Map<String, Object> metadata, int chunkIndex) throws Exception {
        ensureCollectionLoaded();

        String source = (String) metadata.get("_source");
        String id = UUID.nameUUIDFromBytes((source + "_" + chunkIndex).getBytes()).toString();

        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("id", Collections.singletonList(id)));
        fields.add(new InsertParam.Field("content", Collections.singletonList(content)));
        fields.add(new InsertParam.Field("vector", Collections.singletonList(vector)));
        JsonObject metadataJson = GSON.toJsonTree(metadata).getAsJsonObject();
        fields.add(new InsertParam.Field("metadata", Collections.singletonList(metadataJson)));

        InsertParam insertParam = InsertParam.newBuilder()
                .withCollectionName(currentCollection())
                .withFields(fields)
                .build();

        R<MutationResult> insertResponse = milvusClient.insert(insertParam);
        if (insertResponse.getStatus() != 0) {
            throw new RuntimeException("插入向量失败: " + insertResponse.getMessage());
        }
        logger.debug("向量插入成功: id={}, source={}, chunk={}", id, source, chunkIndex);
    }

    private void ensureCollectionLoaded() {
        R<RpcStatus> loadResponse = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                        .withCollectionName(currentCollection())
                        .build()
        );
        if (loadResponse.getStatus() != 0 && loadResponse.getStatus() != 65535) {
            throw new RuntimeException("加载 collection 失败: " + loadResponse.getMessage());
        }
    }

    private String currentCollection() {
        return knowledgeCollectionService.currentCollection();
    }

    private String normalizeSource(Path path) {
        return path.toAbsolutePath().normalize().toString().replace(File.separator, "/");
    }

    private String escapeExprValue(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMetadata(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (raw instanceof JsonObject json) {
            return GSON.fromJson(json, Map.class);
        }
        try {
            return GSON.fromJson(raw.toString(), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String stringValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof JsonElement json && json.isJsonPrimitive()) {
            return json.getAsString();
        }
        return value.toString();
    }

    private boolean booleanValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof JsonElement json && json.isJsonPrimitive()) {
            if (json.getAsJsonPrimitive().isBoolean()) {
                return json.getAsBoolean();
            }
            return Boolean.parseBoolean(json.getAsString());
        }
        return Boolean.parseBoolean(value.toString());
    }

    private long longValue(Object value) {
        if (value == null) {
            return -1L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof JsonElement json && json.isJsonPrimitive()) {
            try {
                return json.getAsLong();
            } catch (Exception ignored) {
                return -1L;
            }
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static final class IndexedDoc {
        private String source = "";
        private String fileName = "";
        private long mtime = -1L;
        private long size = -1L;
        private String parser = "";
        private boolean fallback;
        private boolean unsupported;
    }

    @Getter
    public static class IndexingResult {
        @Setter
        private boolean success;
        @Setter
        private String directoryPath;
        @Setter
        private String collectionName;
        @Setter
        private String parser;
        @Setter
        private int totalFiles;
        private int successCount;
        private int updateCount;
        private int deleteCount;
        private int failCount;
        private int skipCount;
        @Setter
        private LocalDateTime startTime;
        @Setter
        private LocalDateTime endTime;
        @Setter
        private String errorMessage;
        private Map<String, String> failedFiles = new HashMap<>();

        public void incrementSuccessCount() {
            this.successCount++;
        }

        public void incrementUpdateCount() {
            this.updateCount++;
        }

        public void incrementDeleteCount() {
            this.deleteCount++;
        }

        public void incrementFailCount() {
            this.failCount++;
        }

        public void incrementSkipCount() {
            this.skipCount++;
        }

        public long getDurationMs() {
            if (startTime != null && endTime != null) {
                return java.time.Duration.between(startTime, endTime).toMillis();
            }
            return 0;
        }

        public void addFailedFile(String filePath, String error) {
            this.failedFiles.put(filePath, error);
        }
    }
}
