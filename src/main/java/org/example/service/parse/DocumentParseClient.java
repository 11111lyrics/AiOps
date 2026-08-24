package org.example.service.parse;

import java.nio.file.Path;

/**
 * 本地文档解析服务 HTTP 适配。
 */
public interface DocumentParseClient {

    String engine();

    boolean supports(String extension);

    String parseToMarkdown(Path file) throws Exception;

    boolean isReachable();
}
