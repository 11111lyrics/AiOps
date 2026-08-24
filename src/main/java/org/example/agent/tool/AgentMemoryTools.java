package org.example.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.ExperienceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 主动记忆读写工具
 * 让 Agent 在对话过程中自主决定"这条信息值得记住"或"我需要查一下记忆"，
 * 而不是只依赖对话结束后的固定异步提炼流程（MemGPT 式自主记忆管理）。
 */
@Component
public class AgentMemoryTools {

    private static final Logger logger = LoggerFactory.getLogger(AgentMemoryTools.class);

    /** 工具名常量，用于动态构建提示词 */
    public static final String TOOL_SAVE_MEMORY = "saveMemory";
    public static final String TOOL_SEARCH_MEMORY = "searchMemory";

    private final ExperienceService experienceService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public AgentMemoryTools(ExperienceService experienceService) {
        this.experienceService = experienceService;
    }

    @Tool(description = "Proactively save an important reusable piece of knowledge to long-term memory. " +
            "Use this when the user explicitly asks you to remember something (e.g. 'remember that...', '记住...'), " +
            "or when you have confirmed a valuable reusable conclusion during the conversation, such as a " +
            "troubleshooting rule, an environment fact, or an operational convention. " +
            "Do NOT save trivial chit-chat or unverified guesses. " +
            "If a very similar memory already exists, it will be merged and updated instead of duplicated.")
    public String saveMemory(
            @ToolParam(description = "Short title summarizing the memory (a few words)")
            String title,
            @ToolParam(description = "The content to remember: the conclusion, rule or key insight, stated completely and self-contained")
            String content) {
        try {
            String expId = experienceService.saveAgentMemory(title, content);
            if (expId == null) {
                return "{\"status\": \"skipped\", \"message\": \"Memory feature disabled or content empty.\"}";
            }
            return String.format("{\"status\": \"saved\", \"expId\": \"%s\"}", expId);
        } catch (Exception e) {
            logger.error("[工具错误] saveMemory 执行失败", e);
            return String.format("{\"status\": \"error\", \"message\": \"Failed to save memory: %s\"}", e.getMessage());
        }
    }

    @Tool(description = "Search the long-term experience memory for relevant knowledge: past troubleshooting " +
            "experiences, saved rules and conclusions. Use this when the automatically injected experiences are " +
            "not sufficient and you actively need more background, e.g. when investigating a fault pattern or " +
            "when the user asks what the system already knows about a topic. " +
            "Returns matched memories with confidence-and-recency weighted scores.")
    public String searchMemory(
            @ToolParam(description = "Search query describing the knowledge or experience to look up")
            String query) {
        try {
            List<ExperienceService.RecalledExperience> recalled =
                    experienceService.recall(query, experienceService.extractEnvHint(query));
            if (recalled.isEmpty()) {
                return "{\"status\": \"no_results\", \"message\": \"No relevant memories found.\"}";
            }
            List<Map<String, Object>> items = new ArrayList<>();
            for (ExperienceService.RecalledExperience re : recalled) {
                items.add(experienceService.toMemoryItem(re));
            }
            return objectMapper.writeValueAsString(items);
        } catch (Exception e) {
            logger.error("[工具错误] searchMemory 执行失败", e);
            return String.format("{\"status\": \"error\", \"message\": \"Failed to search memory: %s\"}", e.getMessage());
        }
    }
}
