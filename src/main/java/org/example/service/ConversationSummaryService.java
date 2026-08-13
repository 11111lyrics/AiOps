package org.example.service;

import org.example.config.ChatModelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

/**
 * 会话滚动摘要服务
 * 短期记忆采用"最近 N 轮原文 + 更早内容压缩摘要"的 summary buffer 模式：
 * 滑出窗口的旧消息由 LLM 增量合并进摘要（存 MySQL chat_session_summary），
 * 请求时摘要注入 system prompt，窗口内消息以原生多轮 messages 传给 Agent。
 */
@Service
public class ConversationSummaryService {

    private static final Logger logger = LoggerFactory.getLogger(ConversationSummaryService.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChatMemoryService chatMemoryService;

    @Autowired
    private ChatModelFactory chatModelFactory;

    @Value("${memory.window-size:6}")
    private int windowSize;

    @Value("${memory.summary.enabled:true}")
    private boolean enabled;

    @Value("${memory.summary.max-chars:800}")
    private int maxChars;

    @PostConstruct
    public void init() {
        logger.info("会话摘要服务初始化完成, enabled={}, model=deepseek-chat, maxChars={}", enabled, maxChars);
    }

    /**
     * 读取会话的当前滚动摘要，无摘要返回 null。
     */
    public String getSummary(String sessionId) {
        if (!enabled || sessionId == null || sessionId.isEmpty()) {
            return null;
        }
        try {
            return jdbcTemplate.query(
                    "SELECT summary FROM chat_session_summary WHERE session_id = ?",
                    rs -> rs.next() ? rs.getString("summary") : null,
                    sessionId);
        } catch (Exception e) {
            logger.warn("读取会话摘要失败（不影响主流程）- session={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 滚动摘要：若存在已滑出窗口且未被摘要覆盖的消息，则将其与旧摘要合并为新摘要。
     * 设计为对话完成后异步调用，失败不影响主流程。
     */
    public void rollupIfNeeded(String sessionId) {
        if (!enabled || sessionId == null || sessionId.isEmpty()) {
            return;
        }
        try {
            int maxSeq = chatMemoryService.getMaxSeq(sessionId);
            int lastSummarizedSeq = getLastSummarizedSeq(sessionId);
            // 窗口保留最近 windowSize 对（windowSize*2 条），只摘要窗口之外的部分
            int rollupUpTo = maxSeq - windowSize * 2;
            if (rollupUpTo <= lastSummarizedSeq) {
                return;
            }

            List<Map<String, String>> overflow =
                    chatMemoryService.getMessagesBetween(sessionId, lastSummarizedSeq, rollupUpTo);
            if (overflow.isEmpty()) {
                return;
            }

            String oldSummary = getSummary(sessionId);
            String newSummary = callLlmSummarize(oldSummary, overflow);
            if (newSummary == null || newSummary.isBlank()) {
                logger.warn("摘要生成为空，跳过本次滚动 - session={}", sessionId);
                return;
            }

            jdbcTemplate.update(
                    "INSERT INTO chat_session_summary (session_id, summary, last_seq) VALUES (?, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE summary = VALUES(summary), last_seq = VALUES(last_seq)",
                    sessionId, newSummary, rollupUpTo);
            logger.info("会话摘要已滚动更新 - session={}, 覆盖至 seq={}, 摘要长度={}",
                    sessionId, rollupUpTo, newSummary.length());
        } catch (Exception e) {
            logger.warn("滚动摘要失败（不影响主流程）- session={}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 已被摘要覆盖的最大消息 seq，无摘要返回 0。
     */
    private int getLastSummarizedSeq(String sessionId) {
        Integer seq = jdbcTemplate.query(
                "SELECT last_seq FROM chat_session_summary WHERE session_id = ?",
                rs -> rs.next() ? rs.getInt("last_seq") : null,
                sessionId);
        return seq == null ? 0 : seq;
    }

    /**
     * 调用 LLM 将旧摘要与新增溢出消息合并为一份新摘要。固定 DeepSeek，不跟随前端模型切换。
     */
    private String callLlmSummarize(String oldSummary, List<Map<String, String>> overflow) {
        String sys = String.format("""
                你是对话摘要器。请把"已有摘要"与"新增对话内容"合并为一份不超过 %d 字的中文摘要。
                要求：
                1. 保留关键事实：故障现象、涉及的服务/指标/告警名、根因结论、处理动作与结果、重要时间点；
                2. 保留用户的目标、约束与偏好，以及尚未解决的问题；
                3. 去除寒暄、重复表述和无信息量的内容；
                4. 仅输出摘要正文，不要任何额外说明或标题。
                """, maxChars);

        StringBuilder user = new StringBuilder();
        user.append("【已有摘要】\n").append(oldSummary == null || oldSummary.isBlank() ? "（无）" : oldSummary);
        user.append("\n\n【新增对话内容】\n");
        for (Map<String, String> msg : overflow) {
            String role = "user".equals(msg.get("role")) ? "用户" : "助手";
            user.append(role).append(": ").append(msg.get("content")).append("\n");
        }

        logger.info("滚动摘要调用 LLM - provider=deepseek, model=deepseek-chat");
        ChatModel chatModel = chatModelFactory.create(ChatModelFactory.PROVIDER_DEEPSEEK, 0.2, 1500, 0.9);
        ChatResponse response = chatModel.call(new Prompt(List.of(
                new SystemMessage(sys),
                new UserMessage(user.toString()))));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        String summary = response.getResult().getOutput().getText();
        if (summary != null && summary.length() > maxChars * 2) {
            summary = summary.substring(0, maxChars * 2);
        }
        return summary;
    }

    /**
     * 删除会话摘要（清空会话时调用）。
     */
    public void clear(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }
        jdbcTemplate.update("DELETE FROM chat_session_summary WHERE session_id = ?", sessionId);
    }
}
