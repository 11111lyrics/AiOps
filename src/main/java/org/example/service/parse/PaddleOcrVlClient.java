package org.example.service.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.config.DocumentParseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * PaddleOCR-VL 官方 Serving / HPS：POST JSON 到 /layout-parsing。
 * 原生只吃 PDF / 图片，Word 不在此客户端处理。
 */
@Component
public class PaddleOcrVlClient implements DocumentParseClient {

    private static final Logger logger = LoggerFactory.getLogger(PaddleOcrVlClient.class);
    private static final Set<String> EXTENSIONS = Set.of("pdf");
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(3);

    private final DocumentParseProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public PaddleOcrVlClient(DocumentParseProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = ParseHttpSupport.newHttpClient();
    }

    @Override
    public String engine() {
        return DocumentParseProperties.ENGINE_PADDLE;
    }

    @Override
    public boolean supports(String extension) {
        return extension != null && EXTENSIONS.contains(extension.toLowerCase());
    }

    @Override
    public String parseToMarkdown(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        DocumentParseProperties.Paddle paddle = properties.getPaddle();

        ObjectNode body = objectMapper.createObjectNode();
        body.put("file", Base64.getEncoder().encodeToString(bytes));
        body.put("fileType", 0);
        body.put("useChartRecognition", paddle.isUseChartRecognition());
        body.put("useOcrForImageBlock", paddle.isUseOcrForImageBlock());
        body.put("returnMarkdownImages", false);

        String url = ParseHttpSupport.joinUrl(paddle.getBaseUrl(), paddle.getPath());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofMillis(Math.max(1_000L, properties.getTimeoutMs())))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        logger.info("调用 PaddleOCR-VL: {} file={}", url, file.getFileName());
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("PaddleOCR-VL HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
        }

        JsonNode root = objectMapper.readTree(response.body());
        int errorCode = root.path("errorCode").asInt(root.path("error_code").asInt(0));
        if (errorCode != 0) {
            String errorMsg = ParseHttpSupport.firstNonBlankText(root, "errorMsg", "error_msg", "message");
            throw new IllegalStateException("PaddleOCR-VL 返回错误: code=" + errorCode + " " + errorMsg);
        }

        String markdown = extractMarkdown(root);
        if (markdown.isBlank()) {
            throw new IllegalStateException("PaddleOCR-VL 未返回 Markdown");
        }
        logger.info("PaddleOCR-VL 解析完成: {}, markdown={} 字", file.getFileName(), markdown.length());
        return markdown;
    }

    @Override
    public boolean isReachable() {
        DocumentParseProperties.Paddle paddle = properties.getPaddle();
        return ParseHttpSupport.probe(
                httpClient,
                ParseHttpSupport.joinUrl(paddle.getBaseUrl(), paddle.getHealthPath()),
                HEALTH_TIMEOUT);
    }

    private String extractMarkdown(JsonNode root) {
        JsonNode result = root.path("result");
        if (result.isMissingNode() || result.isNull()) {
            result = root;
        }
        JsonNode pages = result.get("layoutParsingResults");
        if (pages == null || pages.isMissingNode()) {
            pages = result.get("layout_parsing_results");
        }
        List<String> parts = new ArrayList<>();
        if (pages != null && pages.isArray()) {
            for (JsonNode page : pages) {
                JsonNode markdown = page.get("markdown");
                String text = ParseHttpSupport.firstNonBlankText(markdown, "text", "markdown_text");
                if (text.isBlank()) {
                    text = ParseHttpSupport.firstNonBlankText(page, "markdown", "md_content", "text");
                }
                if (!text.isBlank()) {
                    parts.add(text.trim());
                }
            }
        }
        if (parts.isEmpty()) {
            String single = ParseHttpSupport.firstNonBlankText(result, "markdown", "md_content", "text");
            if (!single.isBlank()) {
                parts.add(single.trim());
            }
        }
        return String.join("\n\n", parts);
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }
}
