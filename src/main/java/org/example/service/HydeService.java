package org.example.service;

import org.example.config.ChatModelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import java.util.List;

/**
 * HyDE：先让 LLM 写一段「假想知识库片段」，再用这段文字做向量召回。
 * 生成失败时回退为用户原 query，不中断检索。
 */
@Service
public class HydeService {

    private static final Logger logger = LoggerFactory.getLogger(HydeService.class);

    private static final String SYSTEM_PROMPT = """
            你在撰写内部运维知识库的文档片段，不是在回答用户。
            根据问题写出一段可能出现在排障手册中的说明，覆盖现象、可能原因和处置要点。
            只输出文档正文，不要标题、不要问答格式、不要解释你在做什么。
            """;

    private final ChatModelFactory chatModelFactory;
    private final boolean enabled;
    private final int maxTokens;

    public HydeService(ChatModelFactory chatModelFactory,
                       @Value("${rag.hyde.enabled:true}") boolean enabled,
                       @Value("${rag.hyde.max-tokens:400}") int maxTokens) {
        this.chatModelFactory = chatModelFactory;
        this.enabled = enabled;
        this.maxTokens = Math.max(64, maxTokens);
    }

    @PostConstruct
    public void init() {
        logger.info("HyDE 初始化: enabled={}, maxTokens={}", enabled, maxTokens);
    }

    public Rewrite rewrite(String query) {
        if (!StringUtils.hasText(query) || !enabled) {
            return Rewrite.skipped(query);
        }

        try {
            ChatModel chatModel = chatModelFactory.create(
                    ChatModelFactory.PROVIDER_DEEPSEEK, 0.4, maxTokens, 0.9);
            ChatResponse response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(SYSTEM_PROMPT),
                    new UserMessage(query.trim()))));
            String hypothetical = extractText(response);
            if (!StringUtils.hasText(hypothetical) || hypothetical.length() < 20) {
                logger.warn("HyDE 生成过短，回退原 query");
                return Rewrite.skipped(query);
            }
            logger.info("HyDE 已生成假想文档, queryLen={}, hypoLen={}",
                    query.length(), hypothetical.length());
            return Rewrite.applied(query, hypothetical);
        } catch (Exception e) {
            logger.warn("HyDE 生成失败，回退原 query: {}", e.getMessage());
            return Rewrite.skipped(query);
        }
    }

    private String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        if (text == null) {
            return "";
        }
        text = text.replaceAll("(?s)<think>.*?</think>", "").trim();
        if (text.startsWith("```")) {
            int firstNl = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNl > 0 && lastFence > firstNl) {
                text = text.substring(firstNl + 1, lastFence).trim();
            }
        }
        int cap = maxTokens * 2;
        if (text.length() > cap) {
            text = text.substring(0, cap);
        }
        return text.trim();
    }

    public static final class Rewrite {
        public final String originalQuery;
        public final String retrievalText;
        public final boolean applied;

        private Rewrite(String originalQuery, String retrievalText, boolean applied) {
            this.originalQuery = originalQuery == null ? "" : originalQuery;
            this.retrievalText = retrievalText == null ? this.originalQuery : retrievalText;
            this.applied = applied;
        }

        static Rewrite applied(String query, String hypothetical) {
            return new Rewrite(query, hypothetical, true);
        }

        static Rewrite skipped(String query) {
            return new Rewrite(query, query, false);
        }
    }
}
