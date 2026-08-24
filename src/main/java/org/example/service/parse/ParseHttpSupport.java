package org.example.service.parse;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

final class ParseHttpSupport {

    private ParseHttpSupport() {
    }

    /**
     * Uvicorn / FastAPI 不支持 Java HttpClient 默认的 h2c Upgrade。
     * 走 HTTP/2 时 body 解析失败，MinerU 会 422 报 files 缺失。
     */
    static HttpClient newHttpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    static String joinUrl(String baseUrl, String path) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String suffix = path == null ? "" : path.trim();
        if (!suffix.startsWith("/")) {
            suffix = "/" + suffix;
        }
        return base + suffix;
    }

    static String extensionOf(Path file) {
        String name = file.getFileName() != null ? file.getFileName().toString() : "";
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static boolean probe(HttpClient httpClient, String url, Duration timeout) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(timeout)
                    .GET()
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static String firstNonBlankText(JsonNode node, String... fieldNames) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        for (String field : fieldNames) {
            JsonNode child = node.get(field);
            if (child != null && child.isTextual()) {
                String text = child.asText();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    static final class MultipartBody {
        private final String boundary = "WebKitFormBoundary" + UUID.randomUUID().toString().replace("-", "");
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        void addField(String name, String value) throws IOException {
            write("--" + boundary + "\r\n");
            write("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
            write(value == null ? "" : value);
            write("\r\n");
        }

        void addFile(String name, String filename, String contentType, byte[] data) throws IOException {
            write("--" + boundary + "\r\n");
            write("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n");
            write("Content-Type: " + contentType + "\r\n\r\n");
            buffer.write(data);
            write("\r\n");
        }

        byte[] finish() throws IOException {
            write("--" + boundary + "--\r\n");
            return buffer.toByteArray();
        }

        String contentType() {
            return "multipart/form-data; boundary=\"" + boundary + "\"";
        }

        private void write(String text) throws IOException {
            buffer.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
