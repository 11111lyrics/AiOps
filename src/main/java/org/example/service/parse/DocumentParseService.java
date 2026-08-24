package org.example.service.parse;

import jakarta.annotation.PostConstruct;
import org.example.config.DocumentParseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 按开关选择基线或本地解析服务。聊天附件不走这里。
 */
@Service
public class DocumentParseService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentParseService.class);

    private static final long REACHABLE_CACHE_MS = 10_000L;

    private final DocumentParseProperties properties;
    private final Map<String, DocumentParseClient> clients;
    private volatile String cachedReachableEngine;
    private volatile Boolean cachedReachable;
    private volatile long cachedReachableAt;

    public DocumentParseService(DocumentParseProperties properties, List<DocumentParseClient> clients) {
        this.properties = properties;
        this.clients = clients.stream()
                .collect(Collectors.toMap(DocumentParseClient::engine, Function.identity()));
    }

    @PostConstruct
    public void logConfig() {
        logger.info("文档解析: enabled={}, engine={}, collection={}, isolate={}, fallback={}",
                properties.isEnabled(),
                properties.currentEngine(),
                properties.knowledgeCollection(),
                properties.isIsolateCollection(),
                properties.isFallbackToBaseline());
    }

    public ParseOutcome tryParse(Path file) {
        String requested = properties.currentEngine();
        if (!properties.isRemoteEnabled()) {
            return ParseOutcome.skipped(requested);
        }

        DocumentParseClient client = clients.get(requested);
        if (client == null) {
            return fallbackOrThrow(requested, "未注册解析客户端: " + requested);
        }

        String extension = ParseHttpSupport.extensionOf(file);
        if (!client.supports(extension)) {
            logger.info("当前引擎 {} 不支持 .{}，使用基线提取: {}", requested, extension, file.getFileName());
            return ParseOutcome.unsupported(requested, "引擎不支持 ." + extension);
        }

        try {
            String markdown = client.parseToMarkdown(file);
            if (markdown == null || markdown.isBlank()) {
                return fallbackOrThrow(requested, "解析结果为空");
            }
            return ParseOutcome.parsed(requested, markdown);
        } catch (Exception e) {
            logger.warn("调用 {} 解析失败: {} - {}", requested, file.getFileName(), e.getMessage());
            return fallbackOrThrow(requested, e.getMessage());
        }
    }

    /**
     * 这份文件在当前配置下「应当」写入的 _parser。用于判断换引擎后是否要重建。
     */
    public String expectedParserId(Path file) {
        String requested = properties.currentEngine();
        if (!properties.isRemoteEnabled()) {
            return DocumentParseProperties.ENGINE_BASELINE;
        }
        DocumentParseClient client = clients.get(requested);
        if (client == null) {
            return DocumentParseProperties.ENGINE_BASELINE;
        }
        String extension = ParseHttpSupport.extensionOf(file);
        return client.supports(extension) ? requested : DocumentParseProperties.ENGINE_BASELINE;
    }

    public boolean isReachable(String engine) {
        DocumentParseClient client = clients.get(DocumentParseProperties.normalizeEngine(engine));
        return client != null && client.isReachable();
    }

    public boolean isReachableCached(String engine) {
        String normalized = DocumentParseProperties.normalizeEngine(engine);
        long now = System.currentTimeMillis();
        if (normalized.equals(cachedReachableEngine)
                && cachedReachable != null
                && now - cachedReachableAt < REACHABLE_CACHE_MS) {
            return cachedReachable;
        }
        boolean reachable = isReachable(normalized);
        cachedReachableEngine = normalized;
        cachedReachable = reachable;
        cachedReachableAt = now;
        return reachable;
    }

    public DocumentParseProperties properties() {
        return properties;
    }

    private ParseOutcome fallbackOrThrow(String requested, String message) {
        if (properties.isFallbackToBaseline()) {
            return ParseOutcome.fallback(requested, message);
        }
        throw new IllegalStateException("文档解析失败且未开启回退: " + message);
    }

}
