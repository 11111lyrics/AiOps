package org.example.controller;

import lombok.Getter;
import lombok.Setter;
import org.example.service.ChatMemoryService;
import org.example.service.ExperienceLifecycleService;
import org.example.service.ExperienceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 经验管理接口：人工标记有价值 + 评分反馈。
 */
@RestController
@RequestMapping("/api/experience")
public class ExperienceController {

    private static final Logger logger = LoggerFactory.getLogger(ExperienceController.class);

    @Autowired
    private ExperienceService experienceService;

    @Autowired
    private ExperienceLifecycleService lifecycleService;

    @Autowired
    private ChatMemoryService chatMemoryService;

    /**
     * 人工标记某会话为"有价值"，强制按强触发提炼并沉淀。
     */
    @PostMapping("/mark")
    public ResponseEntity<ChatController.ApiResponse<String>> mark(@RequestBody MarkRequest request) {
        try {
            String sessionId = request.getSessionId();
            if (sessionId == null || sessionId.isBlank()) {
                return ResponseEntity.ok(ChatController.ApiResponse.error("会话ID不能为空"));
            }
            List<Map<String, String>> history = chatMemoryService.getRecentHistory(sessionId);
            if (history.isEmpty()) {
                return ResponseEntity.ok(ChatController.ApiResponse.error("会话无历史，无法提炼"));
            }
            // 拼装会话脚本，作为提炼输入，强制强触发
            StringBuilder transcript = new StringBuilder();
            for (Map<String, String> msg : history) {
                transcript.append("user".equals(msg.get("role")) ? "用户: " : "助手: ")
                        .append(msg.get("content")).append("\n");
            }
            experienceService.distillAndStore(sessionId, "（人工标记有价值，请提炼整段会话经验）",
                    transcript.toString(), true);
            return ResponseEntity.ok(ChatController.ApiResponse.success("已提交经验提炼"));
        } catch (Exception e) {
            logger.error("人工标记经验失败", e);
            return ResponseEntity.ok(ChatController.ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * 经验评分反馈：成功复用上调置信度，失败下调。
     */
    @PostMapping("/feedback")
    public ResponseEntity<ChatController.ApiResponse<String>> feedback(@RequestBody FeedbackRequest request) {
        try {
            if (request.getExpId() == null || request.getExpId().isBlank()) {
                return ResponseEntity.ok(ChatController.ApiResponse.error("expId 不能为空"));
            }
            boolean ok = lifecycleService.feedback(request.getExpId(), request.isSuccess());
            return ok
                    ? ResponseEntity.ok(ChatController.ApiResponse.success("评分已更新"))
                    : ResponseEntity.ok(ChatController.ApiResponse.error("未找到该经验"));
        } catch (Exception e) {
            logger.error("经验评分反馈失败", e);
            return ResponseEntity.ok(ChatController.ApiResponse.error(e.getMessage()));
        }
    }

    @Setter
    @Getter
    public static class MarkRequest {
        private String sessionId;
    }

    @Setter
    @Getter
    public static class FeedbackRequest {
        private String expId;
        private boolean success;
    }
}
