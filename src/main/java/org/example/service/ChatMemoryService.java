package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话记忆服务
 * 基于 MySQL 持久化会话历史，替代原先 ChatController 中的内存 Map，重启不丢。
 */
@Service
public class ChatMemoryService {

    private static final Logger logger = LoggerFactory.getLogger(ChatMemoryService.class);

    private final JdbcTemplate jdbcTemplate;

    /** 注入提示词的历史窗口大小（成对计算：用户问题 + AI 回复 = 1 对） */
    @Value("${memory.window-size:6}")
    private int windowSize;

    @Autowired
    public ChatMemoryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 获取最近 windowSize 对（user+assistant）历史消息，按时间正序返回。
     *
     * @param sessionId 会话 ID
     * @return [{"role":"user","content":"..."}, {"role":"assistant","content":"..."}, ...]
     */
    public List<Map<String, String>> getRecentHistory(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return new ArrayList<>();
        }

        int limit = windowSize * 2;
        // 先按 seq 倒序取最近 limit 条，再在内存里反转为正序
        List<Map<String, String>> latestDesc = jdbcTemplate.query(
                "SELECT role, content FROM chat_message WHERE session_id = ? ORDER BY seq DESC LIMIT ?",
                (rs, rowNum) -> {
                    Map<String, String> msg = new HashMap<>();
                    msg.put("role", rs.getString("role"));
                    msg.put("content", rs.getString("content"));
                    return msg;
                },
                sessionId, limit);

        List<Map<String, String>> history = new ArrayList<>(latestDesc);
        java.util.Collections.reverse(history);
        return history;
    }

    /**
     * 获取会话当前最大 seq，无消息返回 0。
     */
    public int getMaxSeq(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return 0;
        }
        Integer maxSeq = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM chat_message WHERE session_id = ?",
                Integer.class, sessionId);
        return maxSeq == null ? 0 : maxSeq;
    }

    /**
     * 获取 seq 在 (fromSeqExclusive, toSeqInclusive] 区间内的消息，按 seq 正序。
     * 供滚动摘要读取"已滑出窗口且尚未被摘要覆盖"的消息。
     */
    public List<Map<String, String>> getMessagesBetween(String sessionId, int fromSeqExclusive, int toSeqInclusive) {
        if (sessionId == null || sessionId.isEmpty() || toSeqInclusive <= fromSeqExclusive) {
            return new ArrayList<>();
        }
        return jdbcTemplate.query(
                "SELECT role, content FROM chat_message WHERE session_id = ? AND seq > ? AND seq <= ? ORDER BY seq ASC",
                (rs, rowNum) -> {
                    Map<String, String> msg = new HashMap<>();
                    msg.put("role", rs.getString("role"));
                    msg.put("content", rs.getString("content"));
                    return msg;
                },
                sessionId, fromSeqExclusive, toSeqInclusive);
    }

    /**
     * 追加一对消息（用户问题 + AI 回复），seq 自增保证顺序。
     */
    @Transactional
    public void appendTurn(String sessionId, String userQuestion, String aiAnswer) {
        if (sessionId == null || sessionId.isEmpty()) {
            logger.warn("appendTurn 收到空 sessionId，跳过持久化");
            return;
        }

        int nextSeq = nextSeq(sessionId);
        jdbcTemplate.update(
                "INSERT INTO chat_message (session_id, seq, role, content) VALUES (?, ?, 'user', ?)",
                sessionId, nextSeq, userQuestion);
        jdbcTemplate.update(
                "INSERT INTO chat_message (session_id, seq, role, content) VALUES (?, ?, 'assistant', ?)",
                sessionId, nextSeq + 1, aiAnswer);

        logger.debug("会话 {} 已持久化一对消息，seq={}/{}", sessionId, nextSeq, nextSeq + 1);
    }

    /**
     * 清空指定会话的全部历史。
     */
    public void clear(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }
        int deleted = jdbcTemplate.update("DELETE FROM chat_message WHERE session_id = ?", sessionId);
        logger.info("会话 {} 历史已清空，删除 {} 条消息", sessionId, deleted);
    }

    /**
     * 会话是否存在（至少有一条消息）。
     */
    public boolean exists(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE session_id = ?", Integer.class, sessionId);
        return count != null && count > 0;
    }

    /**
     * 获取消息对数（总消息数 / 2）。
     */
    public int getPairCount(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return 0;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE session_id = ?", Integer.class, sessionId);
        return count == null ? 0 : count / 2;
    }

    /**
     * 会话创建时间（首条消息时间，epoch 毫秒），无记录时返回 0。
     */
    public long getCreateTime(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return 0L;
        }
        java.sql.Timestamp ts = jdbcTemplate.query(
                "SELECT MIN(create_time) AS ct FROM chat_message WHERE session_id = ?",
                rs -> rs.next() ? rs.getTimestamp("ct") : null,
                sessionId);
        return ts == null ? 0L : ts.getTime();
    }

    /**
     * 计算下一个 seq 值。
     */
    private int nextSeq(String sessionId) {
        Integer maxSeq = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM chat_message WHERE session_id = ?",
                Integer.class, sessionId);
        return (maxSeq == null ? 0 : maxSeq) + 1;
    }
}
