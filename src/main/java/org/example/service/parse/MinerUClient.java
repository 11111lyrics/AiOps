package org.example.service.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MinerU FastAPI：POST multipart 到 /file_parse，取 results.*.md_content。
 */
@Component
public class MinerUClient implements DocumentParseClient {

    private static final Logger logger = LoggerFactory.getLogger(MinerUClient.class);
    private static final Set<String> EXTENSIONS = Set.of("pdf", "docx");
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(3);

    private final DocumentParseProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public MinerUClient(DocumentParseProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = ParseHttpSupport.newHttpClient();
    }

    @Override
    public String engine() {
        return DocumentParseProperties.ENGINE_MINERU;
    }

    @Override
    public boolean supports(String extension) {
        return extension != null && EXTENSIONS.contains(extension.toLowerCase());
    }

    @Override
    public String parseToMarkdown(Path file) throws Exception {
        DocumentParseProperties.Mineru mineru = properties.getMineru();
        byte[] bytes = Files.readAllBytes(file);
        String filename = file.getFileName() != null ? file.getFileName().toString() : "document";
        String contentType = filename.toLowerCase().endsWith(".docx")
                ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                : "application/pdf";

        ParseHttpSupport.MultipartBody multipart = new ParseHttpSupport.MultipartBody();
        multipart.addFile("files", filename, contentType, bytes);
        multipart.addField("return_md", "true");
        multipart.addField("return_images", "false");
        multipart.addField("response_format_zip", "false");
        multipart.addField("backend", mineru.getBackend());
        multipart.addField("parse_method", mineru.getParseMethod());
        multipart.addField("lang_list", mineru.getLang());
        multipart.addField("formula_enable", Boolean.toString(mineru.isFormulaEnable()));
        multipart.addField("table_enable", Boolean.toString(mineru.isTableEnable()));

        String url = ParseHttpSupport.joinUrl(mineru.getBaseUrl(), mineru.getPath());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofMillis(Math.max(1_000L, properties.getTimeoutMs())))
                .header("Content-Type", multipart.contentType())
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.finish()))
                .build();

        logger.info("调用 MinerU: {} backend={} file={}", url, mineru.getBackend(), file.getFileName());
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("MinerU HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
        }

        JsonNode root = objectMapper.readTree(response.body());
        String markdown = extractMarkdown(root);
        if (markdown.isBlank()) {
            throw new IllegalStateException("MinerU 未返回 Markdown");
        }
        logger.info("MinerU 解析完成: {}, markdown={} 字", file.getFileName(), markdown.length());
        return markdown;
    }

    @Override
    public boolean isReachable() {
        DocumentParseProperties.Mineru mineru = properties.getMineru();
        return ParseHttpSupport.probe(
                httpClient,
                ParseHttpSupport.joinUrl(mineru.getBaseUrl(), mineru.getHealthPath()),
                HEALTH_TIMEOUT);
    }

    private String extractMarkdown(JsonNode root) {
        List<String> parts = new ArrayList<>();
        JsonNode results = root.get("results");
        if (results != null && results.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = results.fields();
            while (fields.hasNext()) {
                JsonNode item = fields.next().getValue();
                String text = ParseHttpSupport.firstNonBlankText(item, "md_content", "markdown", "text");
                if (!text.isBlank()) {
                    parts.add(text.trim());
                }
            }
        } else if (results != null && results.isArray()) {
            for (JsonNode item : results) {
                String text = ParseHttpSupport.firstNonBlankText(item, "md_content", "markdown", "text");
                if (!text.isBlank()) {
                    parts.add(text.trim());
                }
            }
        }
        if (parts.isEmpty()) {
            String single = ParseHttpSupport.firstNonBlankText(root, "md_content", "markdown", "text");
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
