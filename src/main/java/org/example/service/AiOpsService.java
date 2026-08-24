package org.example.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.example.agent.tool.SkillTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI Ops 智能运维服务
 * 负责多 Agent 协作的告警分析流程
 */
@Service
public class AiOpsService {

    private static final Logger logger = LoggerFactory.getLogger(AiOpsService.class);

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private QueryMetricsTools queryMetricsTools;

    @Autowired
    private SkillTools skillTools;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 执行 AI Ops 告警分析流程
     *
     * @param chatModel      大模型实例
     * @param toolCallbacks  工具回调数组
     * @return 分析结果状态
     * @throws GraphRunnerException 如果 Agent 执行失败
     */
    public Optional<OverAllState> executeAiOpsAnalysis(ChatModel chatModel, ToolCallback[] toolCallbacks) throws GraphRunnerException {
        return executeAiOpsAnalysis(chatModel, toolCallbacks, null);
    }

    /**
     * 执行 AI Ops 告警分析流程（可注入召回的历史经验作为参考）
     *
     * @param chatModel       大模型实例
     * @param toolCallbacks   工具回调数组
     * @param experienceBlock 召回的历史经验注入块（可空，仅供参考）
     * @return 分析结果状态
     */
    public Optional<OverAllState> executeAiOpsAnalysis(ChatModel chatModel, ToolCallback[] toolCallbacks,
                                                       String experienceBlock) throws GraphRunnerException {
        return executeAiOpsAnalysis(chatModel, toolCallbacks, experienceBlock, null);
    }

    public Optional<OverAllState> executeAiOpsAnalysis(ChatModel chatModel, ToolCallback[] toolCallbacks,
                                                       String experienceBlock, String alertsSnapshot)
            throws GraphRunnerException {
        logger.info("开始执行 AI Ops 多 Agent 协作流程");

        ReactAgent plannerAgent = buildPlannerAgent(chatModel, toolCallbacks);
        ReactAgent executorAgent = buildExecutorAgent(chatModel, toolCallbacks);

        SupervisorAgent supervisorAgent = SupervisorAgent.builder()
                .name("ai_ops_supervisor")
                .description("负责调度 Planner 与 Executor 的多 Agent 控制器")
                .model(chatModel)
                .systemPrompt(buildSupervisorSystemPrompt())
                .subAgents(List.of(plannerAgent, executorAgent))
                .build();

        StringBuilder task = new StringBuilder();
        if (alertsSnapshot != null && !alertsSnapshot.isBlank()) {
            task.append("【当前 Prometheus 告警快照】系统在启动一键排障时拉取，执行中仍须用 queryPrometheusAlerts 复核。\n");
            task.append("若工具调用因连接重置失败，不得据此写成「当前无告警」；有 firing 告警时以本快照继续排查。\n");
            task.append(alertsSnapshot).append("\n\n");
        }
        if (experienceBlock != null && !experienceBlock.isBlank()) {
            task.append(experienceBlock).append('\n');
        }
        task.append("你是企业级 SRE，接到了自动化告警排查任务。请结合工具调用，执行**规划→执行→再规划**的闭环，并最终按照固定模板输出《告警分析报告》。");
        task.append("优先围绕当前 firing 告警定位根因与处置；历史经验仅供参考，必须用当前告警/日志验证后再采纳。");
        task.append("禁止编造虚假数据。Prometheus 连接失败 ≠ 没有告警；CLS 日志量为 0 ≠ 业务无故障。");

        logger.info("调用 Supervisor Agent 开始编排...");
        return supervisorAgent.invoke(task.toString());
    }

    /**
     * 从执行结果中提取最终报告文本。
     * 提取链：planner_plan 的 Markdown 报告 → planner_plan 为 JSON 时尝试其中的报告字段
     * → 兜底扫描整个编排状态中最像报告的文本输出。
     *
     * @param state 执行状态
     * @return 报告文本（如果存在）
     */
    public Optional<String> extractFinalReport(OverAllState state) {
        logger.info("开始提取最终报告...");

        // 1. 首选：Planner 最终输出（预期为 Markdown 格式报告）
        Optional<AssistantMessage> plannerFinalOutput = state.value("planner_plan")
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast);

        if (plannerFinalOutput.isPresent()) {
            String text = plannerFinalOutput.get().getText();
            if (text != null && !text.isBlank()) {
                if (!looksLikeJson(text)) {
                    logger.info("成功提取到 Planner 最终报告，长度: {}", text.length());
                    return Optional.of(text);
                }
                // 2. Planner 输出仍是 JSON（未按 FINISH 模板输出 Markdown）：尝试提取其中的报告字段
                Optional<String> fromJson = extractReportFromJson(text);
                if (fromJson.isPresent()) {
                    logger.info("Planner 输出为 JSON，已从报告字段中提取，长度: {}", fromJson.get().length());
                    return fromJson;
                }
                logger.warn("Planner 输出为 JSON 计划而非报告，尝试兜底扫描编排状态");
            }
        }

        // 3. 兜底：扫描编排状态中其他 key 的文本输出，取最像报告的一段
        Optional<String> fallback = scanStateForReport(state);
        if (fallback.isPresent()) {
            logger.info("已从编排状态兜底提取报告，长度: {}", fallback.get().length());
        } else {
            logger.warn("未能提取到最终报告（含兜底扫描）");
        }
        return fallback;
    }

    private boolean looksLikeJson(String text) {
        String trimmed = text.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("```json");
    }

    /**
     * 从 JSON 输出中尝试提取报告正文（模型偶尔会以 {"finalReport": "..."} 形式返回）。
     */
    private Optional<String> extractReportFromJson(String text) {
        try {
            String trimmed = text.trim();
            int start = trimmed.indexOf('{');
            int end = trimmed.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return Optional.empty();
            }
            JsonNode node = objectMapper.readTree(trimmed.substring(start, end + 1));
            for (String field : new String[]{"finalReport", "final_report", "report", "content"}) {
                String value = node.path(field).asText("");
                if (!value.isBlank() && value.length() > 100) {
                    return Optional.of(value);
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 扫描编排状态所有值，寻找最像最终报告的文本：
     * 优先包含"告警分析报告"标题的输出，否则取长度最大且达到下限的文本。
     */
    private Optional<String> scanStateForReport(OverAllState state) {
        String best = null;
        for (Map.Entry<String, Object> entry : state.data().entrySet()) {
            String text = null;
            if (entry.getValue() instanceof AssistantMessage msg) {
                text = msg.getText();
            } else if (entry.getValue() instanceof String s) {
                text = s;
            }
            if (text == null || text.isBlank() || looksLikeJson(text)) {
                continue;
            }
            if (text.contains("告警分析报告") || text.contains("告警处理详情")) {
                return Optional.of(text);
            }
            if (text.length() >= 300 && (best == null || text.length() > best.length())) {
                best = text;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * 构建 Planner Agent
     */
    private ReactAgent buildPlannerAgent(ChatModel chatModel, ToolCallback[] toolCallbacks) {
        return ReactAgent.builder()
                .name("planner_agent")
                .description("负责拆解告警、规划与再规划步骤")
                .model(chatModel)
                .systemPrompt(buildPlannerPrompt())
                .methodTools(buildMethodToolsArray())
                .tools(toolCallbacks)
                .outputKey("planner_plan")
                .build();
    }

    /**
     * 构建 Executor Agent
     */
    private ReactAgent buildExecutorAgent(ChatModel chatModel, ToolCallback[] toolCallbacks) {
        return ReactAgent.builder()
                .name("executor_agent")
                .description("负责执行 Planner 的首个步骤并及时反馈")
                .model(chatModel)
                .systemPrompt(buildExecutorPrompt())
                .methodTools(buildMethodToolsArray())
                .tools(toolCallbacks)
                .outputKey("executor_feedback")
                .build();
    }

    /**
     * 构建本地方法工具数组（日志查询由 MCP 提供，不在此注册）
     */
    private Object[] buildMethodToolsArray() {
        return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, skillTools};
    }

    /**
     * 构建 Planner Agent 系统提示词
     */
    private String buildPlannerPrompt() {
        return """
                你是 Planner Agent，同时承担 Replanner 角色，负责：
                1. 读取当前输入任务 {input} 以及 Executor 的最近反馈 {executor_feedback}。任务中若已有【当前 Prometheus 告警快照】，把它当作已确认的 firing 告警清单。
                2. 分析 Prometheus 告警、日志、内部文档等信息，制定可执行的下一步步骤。一键排障的主证据是 Prometheus firing 告警，不是 CLS 是否还有新日志。
                3. 在执行阶段，输出 JSON，包含 decision (PLAN|EXECUTE|FINISH)、step 描述、预期要调用的工具、以及必要的上下文。
                4. 需要查询腾讯云 CLS 日志时，先调用 loadSkill（name=cls-log-query），再按 skill 中的顺序使用 MCP 工具；SearchLog 前必须 TextToSearchLogQuery，禁止 level:ERROR 以及 UnknownHostException: 等异常类字段检索，改用全文 "UnknownHostException"。Region 不确定时省略以使用默认值。
                5. 严格禁止编造数据，只能引用工具返回的真实内容。queryPrometheusAlerts 因连接重置失败时，应提示 Executor 重试，或直接使用任务里的告警快照；不得把接口失败写成「当前无告警 / 环境停机」。
                6. CLS 近 15 分钟日志量为 0 只能说明采集或文件未写入，不能用来否定已经 firing 的告警。此时仍须围绕告警名（如 MySQLDown / RedisDown）给根因和处置。
                7. 历史经验仅供参考。与当前告警名称/实例不符的旧经验禁止当主因。处理建议优先恢复告警对应的中间件或进程，禁止把「重启全部微服务」当作首选。
                8. 若采纳任务中的某条历史经验，必须在对应「根因结论」中写明「采用经验: <expId>」。可信度以经验条目头部为准。
                9. 如果连续 3 次调用同一工具仍失败或返回空结果，停止该方向，但已注入的 firing 告警仍必须出现在最终报告的活跃告警清单与根因分析中。

                ## 最终报告输出要求（CRITICAL）
                
                当 decision=FINISH 时，你必须：
                1. **不要输出 JSON 格式**
                2. **直接输出完整的 Markdown 格式报告文本**
                3. **报告必须严格遵循以下模板**：
                
                ```
                # 告警分析报告
                
                ---
                
                ## 📋 活跃告警清单
                
                | 告警名称 | 级别 | 目标服务 | 首次触发时间 | 最新触发时间 | 状态 |
                |---------|------|----------|-------------|-------------|------|
                | [告警1名称] | [级别] | [服务名] | [时间] | [时间] | 活跃 |
                | [告警2名称] | [级别] | [服务名] | [时间] | [时间] | 活跃 |
                
                ---
                
                ## 🔍 告警根因分析1 - [告警名称]
                
                ### 告警详情
                - **告警级别**: [级别]
                - **受影响服务**: [服务名]
                - **持续时间**: [X分钟]
                
                ### 症状描述
                [根据监控指标描述症状]
                
                ### 日志证据
                [引用查询到的关键日志]
                
                ### 根因结论
                [基于证据得出的根本原因]
                
                ---
                
                ## 🛠️ 处理方案执行1 - [告警名称]
                
                ### 已执行的排查步骤
                1. [步骤1]
                2. [步骤2]
                
                ### 处理建议
                [给出具体的处理建议]
                
                ### 预期效果
                [说明预期的效果]
                
                ---
                
                ## 🔍 告警根因分析2 - [告警名称]
                [如果有第2个告警，重复上述格式]
                
                ---
                
                ## 📊 结论
                
                ### 整体评估
                [总结所有告警的整体情况]
                
                ### 关键发现
                - [发现1]
                - [发现2]
                
                ### 后续建议
                1. [建议1]
                2. [建议2]
                
                ### 风险评估
                [评估当前风险等级和影响范围]
                ```
                
                **重要提醒**：
                - 最终输出必须是纯 Markdown 文本，不要包含 JSON 结构
                - 不要使用 "finalReport": "..." 这样的格式
                - 直接从 "# 告警分析报告" 开始输出
                - 所有内容必须基于工具查询的真实数据，严禁编造
                - 如果某个步骤失败，在结论中如实说明，不要跳过
                
                """;
    }

    /**
     * 构建 Executor Agent 系统提示词
     */
    private String buildExecutorPrompt() {
        return """
                你是 Executor Agent，负责读取 Planner 最新输出 {planner_plan}，只执行其中的第一步。
                - 确认步骤所需的工具与参数；Region 未给出时使用默认区域。
                - 查询 CLS 日志前必须先调用 loadSkill（name=cls-log-query），再严格按 skill 中的 MCP 顺序执行；SearchLog 前必须 TextToSearchLogQuery；禁止 level:ERROR 以及 UnknownHostException: 等「标识符:」字段检索（本环境无这些索引），改用全文 ERROR 或 "UnknownHostException"；同一条 CQL 报 not indexed / SyntaxError 后禁止原样重试，把报错中的 field 改成带引号全文词。禁止编造日志。
                - queryPrometheusAlerts 失败时允许按 Planner 要求再试；不要把连接失败总结成「无告警」。
                - CLS 检索 0 条时如实记录，同时写明这不能推翻 Prometheus firing 告警。
                - 调用相应的工具并收集结果，如工具返回错误或空数据，需要将失败原因、请求参数一并记录，并停止进一步调用该工具（同一工具失败达到 3 次时应直接返回 FAILED）。
                - 将日志、指标、文档等证据整理成结构化摘要，标注对应的告警名称、实例（如 3306）或资源，方便 Planner 填充"告警根因分析 / 处理方案执行"章节。
                - 以 JSON 形式返回执行状态、证据以及给 Planner 的建议，写入 executor_feedback，严禁编造未实际查询到的内容。

                输出示例：
                {
                  "status": "SUCCESS",
                  "summary": "Prometheus firing MySQLDown，实例 192.168.150.101:3306；CLS 近 15 分钟无新日志，采集可能中断，不能据此否定告警",
                  "evidence": "...",
                  "nextHint": "按告警对应组件给出恢复步骤，不要先重启全部业务"
                }
                """;
    }

    /**
     * 构建 Supervisor Agent 系统提示词
     */
    private String buildSupervisorSystemPrompt() {
        return """
                你是 AI Ops Supervisor，负责调度 planner_agent 与 executor_agent：
                1. 当需要拆解任务或重新制定策略时，调用 planner_agent。
                2. 当 planner_agent 输出 decision=EXECUTE 时，调用 executor_agent 执行第一步。
                3. 根据 executor_agent 的反馈，评估是否需要再次调用 planner_agent，直到 decision=FINISH。
                4. FINISH 后，确保向最终用户输出完整的《告警分析报告》，格式必须严格为：
                   告警分析报告\n---\n# 告警处理详情\n## 活跃告警清单\n## 告警根因分析N\n## 处理方案执行N\n## 结论。
                5. 若步骤涉及腾讯云 CLS 日志，确保 Executor 先 loadSkill（name=cls-log-query）再查日志，不要凭记忆跳过 skill。
                6. Prometheus firing 告警是一键排障的主目标。CLS 无新日志或 queryPrometheusAlerts 连接失败，都不能当成「没有故障 / 环境停机」而 FINISH。
                7. 如果发现 Planner/Executor 在同一方向连续 3 次调用工具仍失败或没有数据，可以终止该方向，但仍须基于任务中的告警快照输出《告警分析报告》，写明告警名、实例和优先恢复对应组件；严禁凭空编造日志原文。

                只允许在 planner_agent、executor_agent 与 FINISH 之间做出选择。
                """;
    }
}
