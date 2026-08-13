package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对话 / AI Ops / 经验提炼 / 滚动摘要所用的 Chat 模型配置。
 * Embedding、Rerank 仍走 DashScope，不在此切换。
 */
@Component
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    /** 未传 provider 时的默认供应商：deepseek / dashscope */
    private String defaultProvider = "deepseek";

    private Dashscope dashscope = new Dashscope();
    private Deepseek deepseek = new Deepseek();

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = defaultProvider;
    }

    public Dashscope getDashscope() {
        return dashscope;
    }

    public void setDashscope(Dashscope dashscope) {
        this.dashscope = dashscope != null ? dashscope : new Dashscope();
    }

    public Deepseek getDeepseek() {
        return deepseek;
    }

    public void setDeepseek(Deepseek deepseek) {
        this.deepseek = deepseek != null ? deepseek : new Deepseek();
    }

    public static class Dashscope {
        /** 通义对话模型：qwen-plus 工具调用比 turbo 更稳 */
        private String chatModel = "qwen-plus";

        public String getChatModel() {
            return chatModel;
        }

        public void setChatModel(String chatModel) {
            this.chatModel = chatModel;
        }
    }

    public static class Deepseek {
        private String apiKey = "";
        private String baseUrl = "https://api.deepseek.com";
        /** 工具调用用 deepseek-chat，不用 reasoner */
        private String chatModel = "deepseek-chat";

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getChatModel() {
            return chatModel;
        }

        public void setChatModel(String chatModel) {
            this.chatModel = chatModel;
        }
    }
}
