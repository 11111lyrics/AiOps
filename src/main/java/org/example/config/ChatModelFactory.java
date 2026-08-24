package org.example.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

/**
 * 按供应商创建 Spring AI {@link ChatModel}，供对话、AI Ops、经验提炼、滚动摘要共用。
 */
@Component
public class ChatModelFactory {

    private static final Logger logger = LoggerFactory.getLogger(ChatModelFactory.class);

    public static final String PROVIDER_DEEPSEEK = "deepseek";
    public static final String PROVIDER_DASHSCOPE = "dashscope";

    private final LlmProperties llmProperties;

    private final RestClient.Builder restClientBuilder;

    @Value("${spring.ai.dashscope.api-key:}")
    private String dashScopeApiKey;

    public ChatModelFactory(LlmProperties llmProperties, RestClient.Builder restClientBuilder) {
        this.llmProperties = llmProperties;
        this.restClientBuilder = restClientBuilder;
    }

    public ChatModel create(String provider, double temperature, int maxTokens, double topP) {
        String resolved = resolveProvider(provider);
        logger.info("创建 ChatModel - provider={}, model={}, thinking={}, temperature={}, maxTokens={}",
                resolved, modelName(resolved), thinkingEnabled(resolved), temperature, maxTokens);
        if (PROVIDER_DEEPSEEK.equals(resolved)) {
            return createDeepSeek(temperature, maxTokens, topP);
        }
        return createDashScope(temperature, maxTokens, topP);
    }

    /** 经验提炼固定使用 DeepSeek */
    public ChatModel createForDistill() {
        return create(PROVIDER_DEEPSEEK, 0.2, 4000, 0.9);
    }

    public String resolveProvider(String provider) {
        if (!StringUtils.hasText(provider)) {
            return normalize(llmProperties.getDefaultProvider());
        }
        return normalize(provider);
    }

    private String modelName(String resolved) {
        if (PROVIDER_DEEPSEEK.equals(resolved)) {
            return llmProperties.getDeepseek().getChatModel();
        }
        return llmProperties.getDashscope().getChatModel();
    }

    private boolean thinkingEnabled(String resolved) {
        return PROVIDER_DEEPSEEK.equals(resolved) && llmProperties.getDeepseek().isThinking();
    }

    private String normalize(String provider) {
        String p = provider == null ? "" : provider.trim().toLowerCase();
        if (p.isEmpty() || "deepseek".equals(p)) {
            return PROVIDER_DEEPSEEK;
        }
        if ("dashscope".equals(p) || "qwen".equals(p) || "aliyun".equals(p) || "alibaba".equals(p)) {
            return PROVIDER_DASHSCOPE;
        }
        throw new IllegalArgumentException("不支持的模型供应商: " + provider + "，可选 deepseek / dashscope");
    }

    private ChatModel createDashScope(double temperature, int maxTokens, double topP) {
        if (!StringUtils.hasText(dashScopeApiKey)) {
            throw new IllegalStateException("未配置 spring.ai.dashscope.api-key，无法使用通义千问");
        }
        String model = llmProperties.getDashscope().getChatModel();
        DashScopeApi api = DashScopeApi.builder().apiKey(dashScopeApiKey).build();
        return DashScopeChatModel.builder()
                .dashScopeApi(api)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(model)
                        .withTemperature(temperature)
                        .withMaxToken(maxTokens)
                        .withTopP(topP)
                        .build())
                .build();
    }

    private ChatModel createDeepSeek(double temperature, int maxTokens, double topP) {
        String apiKey = llmProperties.getDeepseek().getApiKey();
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("未配置 llm.deepseek.api-key，无法使用 DeepSeek");
        }
        String model = llmProperties.getDeepseek().getChatModel();
        String baseUrl = llmProperties.getDeepseek().getBaseUrl();
        String thinkingType = llmProperties.getDeepseek().isThinking() ? "enabled" : "disabled";
        // Spring AI 1.1.0 带 tools 时 extraBody 会被丢掉；HTTP 拦截器保证每次请求都带 thinking。
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder.clone()
                        .requestInterceptor(new DeepSeekThinkingInterceptor(thinkingType)))
                .build();
        Map<String, Object> extraBody = new HashMap<>();
        extraBody.put("thinking", Map.of("type", thinkingType));
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .temperature(temperature)
                        .maxTokens(maxTokens)
                        .topP(topP)
                        .extraBody(extraBody)
                        .build())
                .build();
    }
}
