package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.example.config.ClsProperties;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.EpisodicMemoryTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 聊天服务
 * 封装 ReactAgent 对话的公共逻辑，包括模型创建、系统提示词构建、Agent 配置等
 */
@Service
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private QueryMetricsTools queryMetricsTools;

    @Autowired
    private EpisodicMemoryTools episodicMemoryTools;

    @Autowired(required = false)  // Mock 模式下才注册，所以设置为 optional,真实环境通过mcp配置注入
    private QueryLogsTools queryLogsTools;

    @Autowired
    private ToolCallbackProvider tools;

    @Autowired
    private ClsProperties clsProperties;

    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;

    /**
     * 创建 DashScope API 实例
     */
    public DashScopeApi createDashScopeApi() {
        return DashScopeApi.builder()
                .apiKey(dashScopeApiKey)
                .build();
    }

    /**
     * 创建 ChatModel
     * @param temperature 控制随机性 (0.0-1.0)
     * @param maxToken 最大输出长度
     * @param topP 核采样参数
     */
    public DashScopeChatModel createChatModel(DashScopeApi dashScopeApi, double temperature, int maxToken, double topP) {
        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                        .withTemperature(temperature)
                        .withMaxToken(maxToken)
                        .withTopP(topP)
                        .build())
                .build();
    }

    /**
     * 创建标准对话 ChatModel（默认参数）
     */
    public DashScopeChatModel createStandardChatModel(DashScopeApi dashScopeApi) {
        return createChatModel(dashScopeApi, 0.7, 2000, 0.9);
    }

    /**
     * 构建系统提示词。
     * 注意：窗口内的对话历史不再拼接进 system prompt，而是通过原生多轮 messages 传给 Agent
     * （见 {@link #buildMessages}）；超出窗口的更早内容以滚动摘要形式注入。
     *
     * @param conversationSummary 会话滚动摘要（可空），覆盖已滑出窗口的早期对话
     * @param experienceBlock     召回的历史经验注入块（可空）
     * @return 完整的系统提示词
     */
    public String buildSystemPrompt(String conversationSummary, String experienceBlock) {
        StringBuilder systemPromptBuilder = new StringBuilder();
        
        // 基础系统提示
        systemPromptBuilder.append("你是一个专业的智能助手，可以获取当前时间、查询天气信息、搜索内部文档知识库，以及查询 Prometheus 告警信息。\n");
        systemPromptBuilder.append("当用户询问时间相关问题时，使用 getCurrentDateTime 工具。\n");
        systemPromptBuilder.append("当用户需要查询公司内部文档、流程、最佳实践或技术指南时，使用 queryInternalDocs 工具。\n");
        systemPromptBuilder.append("当用户需要查询 Prometheus 告警、监控指标或系统告警状态时，使用 queryPrometheusAlerts 工具。\n");
        systemPromptBuilder.append("当用户提及\"之前/上次/以前\"处理过的问题，或需要回忆更早的历史会话内容时，使用 searchPastConversations 工具检索历史对话记录。\n");
        systemPromptBuilder.append("当用户需要查询腾讯云日志（CLS）时，使用 MCP 提供的 CLS 工具，推荐调用顺序：\n");
        systemPromptBuilder.append("1. GetTopicInfoByName：按名称查找日志主题，获取 TopicId（Region 默认 ap-chengdu）\n");
        systemPromptBuilder.append("2. TextToSearchLogQuery：将自然语言转为 CQL 查询语句（务必在 SearchLog 前调用）\n");
        systemPromptBuilder.append("3. SearchLog：执行日志检索（From/To 为毫秒时间戳，默认查近 15 分钟）\n");
        systemPromptBuilder.append("4. DescribeLogContext：查看某条日志的前后上下文（需 PkgId、PkgLogId、Time）\n");
        systemPromptBuilder.append("告警相关可使用 DescribeAlarms、DescribeAlertRecordHistory、GetAlarmLog。\n");
        systemPromptBuilder.append("时间转换使用 ConvertTimeStringToTimestamp / ConvertTimestampToTimeString。\n");
        systemPromptBuilder.append("Region 参数必须使用连字符格式（如 ap-chengdu），禁止编造日志内容。\n\n");
        systemPromptBuilder.append(clsProperties.buildTopicsPromptBlock());

        // 注入召回的历史经验（仅供参考，必须验证）
        if (experienceBlock != null && !experienceBlock.isBlank()) {
            systemPromptBuilder.append(experienceBlock);
        }

        // 注入早期对话的滚动摘要（最近几轮对话以原生 messages 形式单独传入）
        if (conversationSummary != null && !conversationSummary.isBlank()) {
            systemPromptBuilder.append("--- 本会话早期对话摘要（最近几轮对话会以消息形式提供）---\n");
            systemPromptBuilder.append(conversationSummary).append("\n");
            systemPromptBuilder.append("--- 摘要结束 ---\n\n");
        }

        systemPromptBuilder.append("请结合以上信息与对话上下文，回答用户的新问题。");
        
        return systemPromptBuilder.toString();
    }

    /**
     * 把窗口内历史 + 当前问题构建为原生多轮消息列表（历史以 user/assistant 角色消息传递，
     * 而非拼接进 system prompt，保证角色边界清晰、token 利用更高效）。
     *
     * @param history  窗口内历史消息（role/content）
     * @param question 当前用户问题
     * @return 传给 ReactAgent 的消息列表
     */
    public List<Message> buildMessages(List<Map<String, String>> history, String question) {
        List<Message> messages = new ArrayList<>();
        if (history != null) {
            for (Map<String, String> msg : history) {
                String role = msg.get("role");
                String content = msg.get("content");
                if (content == null || content.isBlank()) {
                    continue;
                }
                if ("user".equals(role)) {
                    messages.add(new UserMessage(content));
                } else if ("assistant".equals(role)) {
                    messages.add(new AssistantMessage(content));
                }
            }
        }
        messages.add(new UserMessage(question));
        return messages;
    }

    /**
     * 动态构建方法工具数组
     * 根据 cls.mock-enabled 决定是否包含 QueryLogsTools
     */
    public Object[] buildMethodToolsArray() {
        if (queryLogsTools != null) {
            // Mock 模式：包含 QueryLogsTools
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, episodicMemoryTools, queryLogsTools};
        } else {
            // 真实模式：不包含 QueryLogsTools（由 MCP 提供日志查询功能）
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, episodicMemoryTools};
        }
    }

    /**
     * 获取工具回调列表，mcp服务提供的工具
     */
    public ToolCallback[] getToolCallbacks() {
        return tools.getToolCallbacks();
    }

    /**
     * 记录可用工具列表：mcp服务提供的工具
     */
    public void logAvailableTools() {
        ToolCallback[] toolCallbacks = tools.getToolCallbacks();
        logger.info("可用工具列表:");
        for (ToolCallback toolCallback : toolCallbacks) {
            logger.info(">>> {}", toolCallback.getToolDefinition().name());
        }
    }

    /**
     * 创建 ReactAgent
     * @param chatModel 聊天模型
     * @param systemPrompt 系统提示词
     * @return 配置好的 ReactAgent
     */
    public ReactAgent createReactAgent(DashScopeChatModel chatModel, String systemPrompt) {
        return ReactAgent.builder()
                .name("intelligent_assistant")
                .model(chatModel)
                .systemPrompt(systemPrompt)
                .methodTools(buildMethodToolsArray())
                .tools(getToolCallbacks())
                .build();
    }

    /**
     * 执行 ReactAgent 对话（非流式）
     * @param agent ReactAgent 实例
     * @param question 用户问题
     * @return AI 回复
     */
    public String executeChat(ReactAgent agent, String question) throws GraphRunnerException {
        logger.info("执行 ReactAgent.call() - 自动处理工具调用");
        var response = agent.call(question);
        String answer = response.getText();
        logger.info("ReactAgent 对话完成，答案长度: {}", answer.length());
        return answer;
    }

    /**
     * 执行 ReactAgent 对话（非流式，原生多轮消息）
     * @param agent    ReactAgent 实例
     * @param messages 多轮消息列表（历史 + 当前问题）
     * @return AI 回复
     */
    public String executeChat(ReactAgent agent, List<Message> messages) throws GraphRunnerException {
        logger.info("执行 ReactAgent.call(messages) - 原生多轮消息，共 {} 条", messages.size());
        var response = agent.call(messages);
        String answer = response.getText();
        logger.info("ReactAgent 对话完成，答案长度: {}", answer.length());
        return answer;
    }
}
