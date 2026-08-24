package org.example.service.parse;

import org.example.config.DocumentParseProperties;

/**
 * 单次文档解析结果：记录实际用了哪条提取路径，供 metadata 与增量同步判断。
 */
public class ParseOutcome {

    public enum Status {
        SKIPPED,
        PARSED,
        FALLBACK,
        UNSUPPORTED
    }

    private final Status status;
    private final String requestedEngine;
    private final String parserId;
    private final String markdown;
    private final String message;

    private ParseOutcome(Status status, String requestedEngine, String parserId, String markdown, String message) {
        this.status = status;
        this.requestedEngine = requestedEngine;
        this.parserId = parserId;
        this.markdown = markdown;
        this.message = message;
    }

    public static ParseOutcome skipped(String requestedEngine) {
        return new ParseOutcome(Status.SKIPPED, requestedEngine, DocumentParseProperties.ENGINE_BASELINE, null, null);
    }

    public static ParseOutcome parsed(String engine, String markdown) {
        return new ParseOutcome(Status.PARSED, engine, engine, markdown, null);
    }

    public static ParseOutcome fallback(String requestedEngine, String message) {
        return new ParseOutcome(Status.FALLBACK, requestedEngine, DocumentParseProperties.ENGINE_BASELINE, null, message);
    }

    public static ParseOutcome unsupported(String requestedEngine, String message) {
        return new ParseOutcome(Status.UNSUPPORTED, requestedEngine, DocumentParseProperties.ENGINE_BASELINE, null, message);
    }

    public boolean hasMarkdown() {
        return markdown != null && !markdown.isBlank();
    }

    public boolean shouldRetryOnNextSync() {
        return status == Status.FALLBACK;
    }

    public Status getStatus() {
        return status;
    }

    public String getRequestedEngine() {
        return requestedEngine;
    }

    public String getParserId() {
        return parserId;
    }

    public String getMarkdown() {
        return markdown;
    }

    public String getMessage() {
        return message;
    }
}
