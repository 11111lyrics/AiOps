package org.example.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.support.HttpRequestWrapper;

import java.io.IOException;

/**
 * DeepSeek V4 默认开启思考模式。Spring AI 1.1.0 在带 tools 时会丢掉 extraBody，
 * 后续多轮又无法回传 reasoning_content，接口会 400。
 * 在 HTTP 层把 thinking.type 写进每次 chat/completions 请求。
 */
public class DeepSeekThinkingInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(DeepSeekThinkingInterceptor.class);

    private final String thinkingType;
    private final ObjectMapper mapper = new ObjectMapper();

    public DeepSeekThinkingInterceptor(String thinkingType) {
        this.thinkingType = thinkingType == null || thinkingType.isBlank() ? "disabled" : thinkingType;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String path = request.getURI().getPath();
        if (body == null || body.length == 0 || path == null || !path.contains("chat/completions")) {
            return execution.execute(request, body);
        }
        byte[] patched = patchThinking(body);
        if (patched == body) {
            return execution.execute(request, body);
        }
        HttpRequest wrapped = new HttpRequestWrapper(request) {
            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.putAll(super.getHeaders());
                headers.setContentLength(patched.length);
                return headers;
            }
        };
        return execution.execute(wrapped, patched);
    }

    private byte[] patchThinking(byte[] body) {
        try {
            JsonNode node = mapper.readTree(body);
            if (!node.isObject()) {
                return body;
            }
            ObjectNode obj = (ObjectNode) node;
            ObjectNode thinking = mapper.createObjectNode();
            thinking.put("type", thinkingType);
            obj.set("thinking", thinking);
            return mapper.writeValueAsBytes(obj);
        } catch (Exception e) {
            logger.warn("写入 DeepSeek thinking 参数失败，沿用原请求: {}", e.getMessage());
            return body;
        }
    }
}
