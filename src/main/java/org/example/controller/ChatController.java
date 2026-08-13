package org.example.controller;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.Getter;
import lombok.Setter;
import org.example.config.ChatModelFactory;
import org.example.service.AiOpsService;
import org.example.service.ChatMemoryService;
import org.example.service.ChatService;
import org.example.service.ConversationSummaryService;
import org.example.service.EpisodicMemoryService;
import org.example.service.ExperienceLifecycleService;
import org.example.service.ExperienceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 统一 API 控制器
 * 适配前端接口需求
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    @Autowired
    private AiOpsService aiOpsService;
    
    @Autowired
    private ChatService chatService;

    @Autowired
    private ChatMemoryService chatMemoryService;

    @Autowired
    private ConversationSummaryService conversationSummaryService;

    @Autowired
    private EpisodicMemoryService episodicMemoryService;

    @Autowired
    private ExperienceService experienceService;

    @Autowired
    private ExperienceLifecycleService experienceLifecycleService;

    @Autowired
    private ToolCallbackProvider tools;

    @Autowired
    private ChatModelFactory chatModelFactory;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * 普通对话接口（支持工具调用）
     * 与 /chat_react 逻辑一致，但直接返回完整结果而非流式输出
     */
    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody ChatRequest request) {
        try {
            logger.info("收到对话请求 - SessionId: {}, Provider: {}, Question: {}",
                    request.getId(), request.getProvider(), request.getQuestion());

            // 参数校验
            if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
                logger.warn("问题内容为空");
                return ResponseEntity.ok(ApiResponse.success(ChatResponse.error("问题内容不能为空")));
            }

            // 解析会话 ID（为空则新建）
            String sessionId = resolveSessionId(request.getId());

            // 获取历史消息（从 MySQL 持久化读取最近窗口）
            List<Map<String, String>> history = chatMemoryService.getRecentHistory(sessionId);
            logger.info("会话历史消息对数: {}", history.size() / 2);

            ChatModel chatModel = chatModelFactory.create(request.getProvider(), 0.7, 2000, 0.9);

            // 记录可用工具
            chatService.logAvailableTools();

            logger.info("开始 ReactAgent 对话（支持自动工具调用）");

            // 三层过滤召回相关历史经验，注入提示词
            String experienceBlock = experienceService.recallForPrompt(request.getQuestion());

            // 构建系统提示词（经验 + 早期对话滚动摘要）；窗口内历史走原生多轮 messages
            String summary = conversationSummaryService.getSummary(sessionId);
            String systemPrompt = chatService.buildSystemPrompt(summary, experienceBlock);
            
            // 创建 ReactAgent
            ReactAgent agent = chatService.createReactAgent(chatModel, systemPrompt);
            
            // 执行对话（历史 + 当前问题以原生多轮消息传入）
            List<Message> messages = chatService.buildMessages(history, request.getQuestion());
            String fullAnswer = chatService.executeChat(agent, messages);
            
            // 更新会话历史（持久化到 MySQL）
            chatMemoryService.appendTurn(sessionId, request.getQuestion(), fullAnswer);
            logger.info("已更新会话历史 - SessionId: {}, 当前消息对数: {}", 
                sessionId, chatMemoryService.getPairCount(sessionId));

            // 异步：分级提炼经验 + 滚动摘要 + 情景记忆归档，不阻塞响应
            final String q = request.getQuestion();
            executor.execute(() -> {
                experienceService.distillAndStore(sessionId, q, fullAnswer, false);
                conversationSummaryService.rollupIfNeeded(sessionId);
                episodicMemoryService.archiveTurn(sessionId, q, fullAnswer);
            });
            
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.success(fullAnswer, sessionId)));

        } catch (Exception e) {
            logger.error("对话失败", e);
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.error(e.getMessage())));
        }
    }

    /**
     * 清空会话历史
     */
    @PostMapping("/chat/clear")
    public ResponseEntity<ApiResponse<String>> clearChatHistory(@RequestBody ClearRequest request) {
        try {
            logger.info("收到清空会话历史请求 - SessionId: {}", request.getId());

            if (request.getId() == null || request.getId().isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error("会话ID不能为空"));
            }

            if (chatMemoryService.exists(request.getId())) {
                chatMemoryService.clear(request.getId());
                conversationSummaryService.clear(request.getId());
                return ResponseEntity.ok(ApiResponse.success("会话历史已清空"));
            } else {
                return ResponseEntity.ok(ApiResponse.error("会话不存在"));
            }

        } catch (Exception e) {
            logger.error("清空会话历史失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * ReactAgent 对话接口（SSE 流式模式，支持多轮对话，支持自动工具调用，例如获取当前时间，查询日志，告警等）
     * 支持 session 管理，保留对话历史
     */
    @PostMapping(value = "/chat_stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时

        // 参数校验
        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            logger.warn("问题内容为空");
            try {
                emitter.send(SseEmitter.event().name("message").data(SseMessage.error("问题内容不能为空"), MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        executor.execute(() -> {
            try {
                logger.info("收到 ReactAgent 对话请求 - SessionId: {}, Provider: {}, Question: {}",
                        request.getId(), request.getProvider(), request.getQuestion());

                // 解析会话 ID（为空则新建）
                String sessionId = resolveSessionId(request.getId());

                // 获取历史消息（从 MySQL 持久化读取最近窗口）
                List<Map<String, String>> history = chatMemoryService.getRecentHistory(sessionId);
                logger.info("ReactAgent 会话历史消息对数: {}", history.size() / 2);

                ChatModel chatModel = chatModelFactory.create(request.getProvider(), 0.7, 2000, 0.9);

                // 记录可用工具
                chatService.logAvailableTools();

                logger.info("开始 ReactAgent 流式对话（支持自动工具调用）");

                // 三层过滤召回相关历史经验，注入提示词
                String experienceBlock = experienceService.recallForPrompt(request.getQuestion());

                // 构建系统提示词（经验 + 早期对话滚动摘要）；窗口内历史走原生多轮 messages
                String summary = conversationSummaryService.getSummary(sessionId);
                String systemPrompt = chatService.buildSystemPrompt(summary, experienceBlock);
                
                // 创建 ReactAgent
                ReactAgent agent = chatService.createReactAgent(chatModel, systemPrompt);
                
                // 用于累积完整答案
                StringBuilder fullAnswerBuilder = new StringBuilder();
                
                // 使用 agent.stream() 进行流式对话（历史 + 当前问题以原生多轮消息传入）
                List<Message> messages = chatService.buildMessages(history, request.getQuestion());
                Flux<NodeOutput> stream = agent.stream(messages);
                
                stream.subscribe(
                    output -> {
                        try {
                            // 检查是否为 StreamingOutput 类型
                            if (output instanceof StreamingOutput streamingOutput) {
                                OutputType type = streamingOutput.getOutputType();
                                
                                // 处理模型推理的流式输出
                                if (type == OutputType.AGENT_MODEL_STREAMING) {
                                    // 流式增量内容，逐步显示
                                    String chunk = streamingOutput.message().getText();
                                    if (chunk != null && !chunk.isEmpty()) {
                                        fullAnswerBuilder.append(chunk);
                                        
                                        // 实时发送到前端
                                        emitter.send(SseEmitter.event()
                                                .name("message")
                                                .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));
                                        
                                        logger.debug("流式增量: {}", chunk);
                                    }
                                } else if (type == OutputType.AGENT_MODEL_FINISHED) {
                                    logger.debug("模型输出完成");
                                } else if (type == OutputType.AGENT_TOOL_FINISHED) {
                                    logger.info("工具调用完成: {}", output.node());
                                } else if (type == OutputType.AGENT_HOOK_FINISHED) {
                                    logger.debug("Hook 执行完成: {}", output.node());
                                }
                            }
                        } catch (IOException e) {
                            logger.error("发送流式消息失败", e);
                            throw new RuntimeException(e);
                        }
                    },
                    error -> {
                        // 错误处理
                        logger.error("ReactAgent 流式对话失败", error);
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("message")
                                    .data(SseMessage.error(error.getMessage()), MediaType.APPLICATION_JSON));
                        } catch (IOException ex) {
                            logger.error("发送错误消息失败", ex);
                        }
                        emitter.completeWithError(error);
                    },
                    () -> {
                        // 完成处理
                        try {
                            String fullAnswer = fullAnswerBuilder.toString();
                            logger.info("ReactAgent 流式对话完成 - SessionId: {}, 答案长度: {}", 
                                request.getId(), fullAnswer.length());
                            
                            // 更新会话历史（持久化到 MySQL）
                            chatMemoryService.appendTurn(sessionId, request.getQuestion(), fullAnswer);
                            logger.info("已更新会话历史 - SessionId: {}, 当前消息对数: {}", 
                                sessionId, chatMemoryService.getPairCount(sessionId));

                            // 异步：分级提炼经验 + 滚动摘要 + 情景记忆归档，不阻塞响应
                            executor.execute(() -> {
                                experienceService.distillAndStore(
                                        sessionId, request.getQuestion(), fullAnswer, false);
                                conversationSummaryService.rollupIfNeeded(sessionId);
                                episodicMemoryService.archiveTurn(sessionId, request.getQuestion(), fullAnswer);
                            });
                            
                            // 发送完成标记（data 携带服务端实际使用的 sessionId，便于客户端续接会话）
                            emitter.send(SseEmitter.event()
                                    .name("message")
                                    .data(SseMessage.done(sessionId), MediaType.APPLICATION_JSON));
                            emitter.complete();
                        } catch (IOException e) {
                            logger.error("发送完成消息失败", e);
                            emitter.completeWithError(e);
                        }
                    }
                );

            } catch (Exception e) {
                logger.error("ReactAgent 对话初始化失败", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(SseMessage.error(e.getMessage()), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * AI 智能运维接口（SSE 流式模式）- 自动分析告警并生成运维报告
     * 无需用户输入，自动执行告警分析流程
     */
    @PostMapping(value = "/ai_ops", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter aiOps(@RequestBody(required = false) ChatRequest request) {
        SseEmitter emitter = new SseEmitter(600000L); // 10分钟超时（告警分析可能较慢）
        String provider = request != null ? request.getProvider() : null;

        executor.execute(() -> {
            try {
                logger.info("收到 AI 智能运维请求 - provider={}, 启动多 Agent 协作流程", provider);

                ChatModel chatModel = chatModelFactory.create(provider, 0.3, 8000, 0.9);

                ToolCallback[] toolCallbacks = tools.getToolCallbacks();

                emitter.send(SseEmitter.event().name("message").data(SseMessage.content("正在读取告警并拆解任务...\n")));

                // 召回相关历史经验，作为 Planner 的参考输入，并记录命中经验 ID 用于闭环评分
                String opsRecallQuery = "系统告警 CPU 内存 磁盘 响应时间 根因 排查 运维";
                List<ExperienceService.RecalledExperience> recalled =
                        experienceService.recall(opsRecallQuery, null);
                String experienceBlock = experienceService.formatExperienceBlock(recalled);
                List<String> recalledExpIds = new ArrayList<>();
                for (ExperienceService.RecalledExperience re : recalled) {
                    recalledExpIds.add(re.getExpId());
                }

                // 调用 AiOpsService 执行分析流程（注入历史经验）
                Optional<OverAllState> overAllStateOptional =
                        aiOpsService.executeAiOpsAnalysis(chatModel, toolCallbacks, experienceBlock);

                if (overAllStateOptional.isEmpty()) {
                    // 闭环负反馈：编排走完但没有产出有效结果，本次采纳的召回经验未起效，下调置信度
                    executor.execute(() -> experienceLifecycleService.feedbackBatch(recalledExpIds, false));
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.error("多 Agent 编排未获取到有效结果"), MediaType.APPLICATION_JSON));
                    emitter.complete();
                    return;
                }

                OverAllState state = overAllStateOptional.get();
                logger.info("AI Ops 编排完成，开始提取最终报告...");

                // 提取最终报告
                Optional<String> finalReportOptional = aiOpsService.extractFinalReport(state);

                // 输出最终报告
                if (finalReportOptional.isPresent()) {
                    String finalReportText = finalReportOptional.get();
                    logger.info("提取到 Planner 最终报告，长度: {}", finalReportText.length());
                    
                    // 发送分隔线
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("\n\n" + "=".repeat(60) + "\n"), MediaType.APPLICATION_JSON));
                    
                    // 发送完整的告警分析报告
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("📋 **告警分析报告**\n\n"), MediaType.APPLICATION_JSON));
                    
                    int chunkSize = 50;
                    for (int i = 0; i < finalReportText.length(); i += chunkSize) {
                        int end = Math.min(i + chunkSize, finalReportText.length());
                        String chunk = finalReportText.substring(i, end);
                        
                        emitter.send(SseEmitter.event().name("message")
                                .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));
                    }
                    
                    // 发送结束分隔线
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("\n" + "=".repeat(60) + "\n\n"), MediaType.APPLICATION_JSON));
                    
                    logger.info("最终报告已完整输出");

                    // 闭环：报告成功生成 → 对本次采纳的召回经验自动回写成功评分，并将报告沉淀为新经验
                    final String reportForDistill = finalReportText;
                    executor.execute(() -> {
                        experienceLifecycleService.feedbackBatch(recalledExpIds, true);
                        experienceService.distillAndStore("aiops-" + System.currentTimeMillis(),
                                "自动告警分析任务", reportForDistill, false);
                    });
                } else {
                    logger.warn("未能提取到 Planner 最终报告");
                    // 闭环负反馈：流程完成但未产出可用报告，对本次采纳的召回经验回写失败评分
                    executor.execute(() -> experienceLifecycleService.feedbackBatch(recalledExpIds, false));
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("⚠️ 多 Agent 流程已完成，但未能生成最终报告。"), MediaType.APPLICATION_JSON));
                }

                emitter.send(SseEmitter.event().name("message").data(SseMessage.done(), MediaType.APPLICATION_JSON));
                emitter.complete();
                logger.info("AI Ops 多 Agent 编排完成");

            } catch (Exception e) {
                logger.error("AI Ops 多 Agent 协作失败", e);
                try {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.error("AI Ops 流程失败: " + e.getMessage()), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }


    /**
     * 获取会话信息
     */
    @GetMapping("/chat/session/{sessionId}")
    public ResponseEntity<ApiResponse<SessionInfoResponse>> getSessionInfo(@PathVariable String sessionId) {
        try {
            logger.info("收到获取会话信息请求 - SessionId: {}", sessionId);

            if (chatMemoryService.exists(sessionId)) {
                SessionInfoResponse response = new SessionInfoResponse();
                response.setSessionId(sessionId);
                response.setMessagePairCount(chatMemoryService.getPairCount(sessionId));
                response.setCreateTime(chatMemoryService.getCreateTime(sessionId));
                return ResponseEntity.ok(ApiResponse.success(response));
            } else {
                return ResponseEntity.ok(ApiResponse.error("会话不存在"));
            }

        } catch (Exception e) {
            logger.error("获取会话信息失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 解析会话 ID：为空时生成新的 UUID。
     */
    private String resolveSessionId(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return UUID.randomUUID().toString();
        }
        return sessionId;
    }

    /**
     * 聊天请求
     */
    @Setter
    @Getter
    public static class ChatRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;
        
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Question")
        @com.fasterxml.jackson.annotation.JsonAlias({"question", "QUESTION"})
        private String Question;

        @com.fasterxml.jackson.annotation.JsonProperty(value = "Provider")
        @com.fasterxml.jackson.annotation.JsonAlias({"provider", "PROVIDER"})
        private String Provider;

    }

    /**
     * 清空会话请求
     */
    @Setter
    @Getter
    public static class ClearRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;
    }

    // ==================== 内部类 ====================

    /**
     * 会话信息响应
     */
    @Setter
    @Getter
    public static class SessionInfoResponse {
        private String sessionId;
        private int messagePairCount;
        private long createTime;
    }

    /**
     * 统一聊天响应格式
     * 适用于所有普通返回模式的对话接口
     */
    @Setter
    @Getter
    public static class ChatResponse {
        private boolean success;
        private String answer;
        private String errorMessage;
        /** 服务端实际使用的会话 ID（客户端未传 Id 时由服务端生成，回传以便续接会话） */
        private String sessionId;

        public static ChatResponse success(String answer, String sessionId) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(true);
            response.setAnswer(answer);
            response.setSessionId(sessionId);
            return response;
        }

        public static ChatResponse error(String errorMessage) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(false);
            response.setErrorMessage(errorMessage);
            return response;
        }
    }

    /**
     * 统一 SSE 流式消息格式
     * 适用于所有 SSE 流式返回模式的对话接口
     */
    @Setter
    @Getter
    public static class SseMessage {
        private String type;  // content: 内容块, error: 错误, done: 完成
        private String data;

        public static SseMessage content(String data) {
            SseMessage message = new SseMessage();
            message.setType("content");
            message.setData(data);
            return message;
        }

        public static SseMessage error(String errorMessage) {
            SseMessage message = new SseMessage();
            message.setType("error");
            message.setData(errorMessage);
            return message;
        }

        public static SseMessage done() {
            return done(null);
        }

        /**
         * 完成标记，data 可携带服务端实际使用的 sessionId。
         */
        public static SseMessage done(String sessionId) {
            SseMessage message = new SseMessage();
            message.setType("done");
            message.setData(sessionId);
            return message;
        }
    }


    @Getter
    @Setter
    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public static <T> ApiResponse<T> success(T data) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(200);
            response.setMessage("success");
            response.setData(data);
            return response;
        }

        public static <T> ApiResponse<T> error(String message) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(500);
            response.setMessage(message);
            return response;
        }

    }
}
