package org.example.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.EpisodicMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 历史会话检索工具（情景记忆）
 * 让 Agent 能跨会话回忆过去处理过的问题，例如"上次数据库告警是怎么解决的"。
 */
@Component
public class EpisodicMemoryTools {

    private static final Logger logger = LoggerFactory.getLogger(EpisodicMemoryTools.class);

    /** 工具名常量，用于动态构建提示词 */
    public static final String TOOL_SEARCH_PAST_CONVERSATIONS = "searchPastConversations";

    private final EpisodicMemoryService episodicMemoryService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public EpisodicMemoryTools(EpisodicMemoryService episodicMemoryService) {
        this.episodicMemoryService = episodicMemoryService;
    }

    @Tool(description = "Search past conversation history (episodic memory) across all sessions. " +
            "Use this when the user refers to something discussed or handled before, e.g. " +
            "'how did we fix that database alert last time', 'what was the conclusion of the previous incident', " +
            "or when past troubleshooting context would help answer the current question. " +
            "Returns matched past Q&A records with timestamps and similarity scores.")
    public String searchPastConversations(
            @ToolParam(description = "Search query describing the past topic, incident or question to recall")
            String query) {
        try {
            List<EpisodicMemoryService.EpisodicRecord> records = episodicMemoryService.search(query);
            if (records.isEmpty()) {
                return "{\"status\": \"no_results\", \"message\": \"No relevant past conversations found.\"}";
            }
            return objectMapper.writeValueAsString(records);
        } catch (Exception e) {
            logger.error("[工具错误] searchPastConversations 执行失败", e);
            return String.format("{\"status\": \"error\", \"message\": \"Failed to search past conversations: %s\"}",
                    e.getMessage());
        }
    }
}
