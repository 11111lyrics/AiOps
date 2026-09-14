package org.example.aiops.node;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.Agent;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.example.agent.tool.SkillTools;
import org.example.aiops.IncidentProgressBus;
import org.example.aiops.IncidentRepository;
import org.example.aiops.config.AiOpsOrchestrationProperties;
import org.example.aiops.config.AiOpsOrchestrationProperties.Playbook;
import org.example.aiops.config.AiOpsOrchestrationProperties.Rca;
import org.example.aiops.model.Blackboard;
import org.example.aiops.model.Incident;
import org.example.aiops.model.RcaConclusion;
import org.example.config.ChatModelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 节点 ②：根因分析。唯一调用 LLM 的排障节点。
 *
 * <p>引擎二选一（{@code aiops.orchestration.rca.engine}）：
 * <ul>
 *   <li>{@code plan-execute}（默认）：Planner / Executor / Supervisor 三个 Agent 做「规划→执行→再规划」闭环，
 *       与旧 {@code AiOpsService} 同源；Supervisor 内层图对外层七节点图是黑盒，不共用白板。</li>
 *   <li>{@code react}：单个 ReactAgent 一次跑完。</li>
 * </ul>
 *
 * <p>两种引擎的产物相同：《告警分析报告》Markdown（写 {@code report_markdown}）+ {@link RcaConclusion}
 * （写 {@code rca_conclusion}）。结论优先取报告尾部的 ```json 块；缺失时再用一次无工具 LLM 调用从报告抽取；
 * 仍失败则 {@code parsed=false}，下游只靠告警名 / 关键词兜底选 playbook，风险按 L2 处理。
 */
@Component
public class RootCauseAnalysisNode extends AbstractOpsNode {

    public static final String NAME = "rca";

    private static final Logger logger = LoggerFactory.getLogger(RootCauseAnalysisNode.class);
    private static final Pattern JSON_FENCE = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final String REPORT_TITLE = "告警分析报告";
    /** Supervisor 内层图里 Planner / Executor 的输出 key */
    private static final String KEY_PLANNER = "planner_plan";
    private static final String KEY_EXECUTOR = "executor_feedback";

    private final ChatModelFactory chatModelFactory;
    private final ToolCallbackProvider mcpTools;
    private final DateTimeTools dateTimeTools;
    private final InternalDocsTools internalDocsTools;
    private final QueryMetricsTools queryMetricsTools;
    private final SkillTools skillTools;
    private final AiOpsOrchestrationProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final ExecutorService rcaExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "aiops-rca");
        t.setDaemon(true);
        return t;
    });

    public RootCauseAnalysisNode(IncidentProgressBus bus, IncidentRepository repository,
                                 ChatModelFactory chatModelFactory, ToolCallbackProvider mcpTools,
                                 DateTimeTools dateTimeTools, InternalDocsTools internalDocsTools,
                                 QueryMetricsTools queryMetricsTools, SkillTools skillTools,
                                 AiOpsOrchestrationProperties properties) {
        super(bus, repository);
        this.chatModelFactory = chatModelFactory;
        this.mcpTools = mcpTools;
        this.dateTimeTools = dateTimeTools;
        this.internalDocsTools = internalDocsTools;
        this.queryMetricsTools = queryMetricsTools;
        this.skillTools = skillTools;
        this.properties = properties;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    protected void run(OverAllState state, Map<String, Object> out) throws Exception {
        Incident incident = Blackboard.incident(state).orElseGet(Incident::new);
        String experienceBlock = Blackboard.experienceBlock(state);
        String incidentId = Blackboard.incidentId(state);
        Rca cfg = properties.getRca();
        boolean planExecute = cfg.isPlanExecute();

        progress(state, out, planExecute
                ? "【② 根因分析】Planner / Executor 正在按「规划→执行→再规划」查 Prometheus / CLS / 知识库并撰写报告…"
                : "【② 根因分析】RCA Agent 正在查 Prometheus / CLS / 知识库并撰写报告…");

        ChatModel chatModel = chatModelFactory.create(Blackboard.provider(state),
                cfg.getTemperature(), cfg.getMaxTokens(), 0.9);
        String task = buildTask(incident, experienceBlock);
        ToolCallback[] callbacks = mcpTools == null ? new ToolCallback[0] : mcpTools.getToolCallbacks();

        long started = System.currentTimeMillis();
        String raw = planExecute
                ? runGuarded(incidentId, cfg, () -> runPlanExecute(chatModel, callbacks, task))
                : runGuarded(incidentId, cfg, () -> runReact(chatModel, callbacks, task));
        audit(state, "engine", planExecute ? Rca.ENGINE_PLAN_EXECUTE : Rca.ENGINE_REACT);
        audit(state, "raw_answer", raw);

        Parsed parsed = parse(raw);
        RcaConclusion conclusion = parsed.conclusion;
        String report = parsed.report;
        if (report.isBlank()) {
            report = "# " + REPORT_TITLE + "\n\n（RCA 未产出报告正文）";
        }

        if (!conclusion.isParsed() && cfg.isExtractConclusionWithLlm() && !report.isBlank()) {
            progress(state, out, "【② 根因分析】报告缺少结构化结论，正在从报告中抽取…");
            RcaConclusion extracted = extractConclusionFromReport(chatModel, incident, report);
            if (extracted != null && extracted.isParsed()) {
                extracted.setParseNote("extracted_from_report");
                conclusion = extracted;
            }
        }
        sanitizeConclusion(conclusion);

        out.put(Blackboard.RCA_CONCLUSION, conclusion);
        out.put(Blackboard.REPORT_MARKDOWN, report);
        repository.updateReport(incidentId, report);
        audit(state, "conclusion", Blackboard.toJsonQuietly(conclusion));

        StringBuilder line = new StringBuilder("【② 根因分析】");
        if (conclusion.isParsed()) {
            line.append("根因：").append(abbreviate(conclusion.getRootCause(), 80))
                    .append("（置信度 ").append(String.format("%.2f", conclusion.getConfidence())).append("）");
            if (!conclusion.getProposedPlaybooks().isEmpty()) {
                line.append("；建议 playbook：")
                        .append(conclusion.getProposedPlaybooks().get(0).getPlaybookId());
            }
            if ("extracted_from_report".equals(conclusion.getParseNote())) {
                line.append("；结论由报告二次抽取");
            }
        } else {
            line.append("报告已生成，但结构化结论缺失（").append(conclusion.getParseNote())
                    .append("），后续只按告警名 / 关键词兜底选 playbook，风险按 L2 处理");
        }
        line.append("；耗时 ").append((System.currentTimeMillis() - started) / 1000).append("s");
        progress(state, out, line.toString());
        done(state, line.toString());

        // 报告正文单独作为 content 推送：与旧 /api/ai_ops 输出格式一致（评测脚本按此累计）
        bus.content(incidentId, "\n" + "=".repeat(60) + "\n");
        bus.content(incidentId, "📋 **" + REPORT_TITLE + "**\n\n");
        int chunk = 400;
        for (int i = 0; i < report.length(); i += chunk) {
            bus.content(incidentId, report.substring(i, Math.min(report.length(), i + chunk)));
        }
        bus.content(incidentId, "\n" + "=".repeat(60) + "\n\n");
    }

    // ==================== 图套图护栏：超时 + 心跳 ====================

    /**
     * 内层 Agent 在独立线程跑；本线程按 heartbeat 间隔向 SSE 推一行心跳，超过 timeout 就取消并抛错，
     * 避免 Supervisor 循环时 SSE 长时间无输出直到连接断开。
     */
    private String runGuarded(String incidentId, Rca cfg, Callable<String> body) throws Exception {
        Future<String> future = rcaExecutor.submit(body);
        long deadline = System.currentTimeMillis() + Math.max(30, cfg.getTimeoutSeconds()) * 1000L;
        long beat = Math.max(5, cfg.getHeartbeatSeconds()) * 1000L;
        long started = System.currentTimeMillis();
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                future.cancel(true);
                throw new TimeoutException("RCA 超过 " + cfg.getTimeoutSeconds() + "s 未结束，已中止");
            }
            try {
                return future.get(Math.min(beat, remaining), TimeUnit.MILLISECONDS);
            } catch (TimeoutException waiting) {
                long elapsed = (System.currentTimeMillis() - started) / 1000;
                bus.content(incidentId, "【② 根因分析】仍在排查（已 " + elapsed + "s）…\n");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof Exception ex) {
                    throw ex;
                }
                throw new IllegalStateException(cause);
            }
        }
    }

    // ==================== 引擎 1：plan-execute（Planner / Executor / Supervisor） ====================

    private String runPlanExecute(ChatModel chatModel, ToolCallback[] callbacks, String task) throws Exception {
        ReactAgent planner = ReactAgent.builder()
                .name("planner_agent")
                .description("负责拆解告警、规划与再规划步骤，最终输出《告警分析报告》")
                .model(chatModel)
                .systemPrompt(buildPlannerPrompt())
                .methodTools(dateTimeTools, internalDocsTools, queryMetricsTools, skillTools)
                .tools(callbacks)
                .outputKey(KEY_PLANNER)
                .build();
        ReactAgent executor = ReactAgent.builder()
                .name("executor_agent")
                .description("负责执行 Planner 的首个步骤并及时反馈证据")
                .model(chatModel)
                .systemPrompt(buildExecutorPrompt())
                .methodTools(dateTimeTools, internalDocsTools, queryMetricsTools, skillTools)
                .tools(callbacks)
                .outputKey(KEY_EXECUTOR)
                .build();

        List<Agent> subAgents = new ArrayList<>();
        subAgents.add(planner);
        subAgents.add(executor);
        SupervisorAgent supervisor = SupervisorAgent.builder()
                .name("ai_ops_supervisor")
                .description("负责调度 Planner 与 Executor 的多 Agent 控制器")
                .model(chatModel)
                .systemPrompt(buildSupervisorPrompt())
                .subAgents(subAgents)
                .compileConfig(CompileConfig.builder()
                        .recursionLimit(Math.max(6, properties.getRca().getRecursionLimit()))
                        .build())
                .build();

        logger.info("RCA plan-execute：调用 Supervisor 开始编排");
        Optional<OverAllState> inner = supervisor.invoke(task);
        if (inner.isEmpty()) {
            throw new IllegalStateException("Supervisor 未返回状态");
        }
        return extractFinalReport(inner.get())
                .orElseThrow(() -> new IllegalStateException("Planner / Executor 未产出《告警分析报告》"));
    }

    /**
     * 从内层图状态提取报告文本。三级链（沿用旧 AiOpsService）：
     * 1. planner_plan 已是 Markdown → 直接用；
     * 2. planner_plan 仍是 JSON → 抽 finalReport / final_report / report / content；
     * 3. 扫描整份状态，优先含「告警分析报告」的文本，否则取最长的实质性输出。
     */
    Optional<String> extractFinalReport(OverAllState state) {
        Optional<String> plannerText = state.value(KEY_PLANNER).flatMap(RootCauseAnalysisNode::textOf);
        if (plannerText.isPresent() && !plannerText.get().isBlank()) {
            Optional<String> report = extractReportText(plannerText.get());
            if (report.isPresent()) {
                logger.info("RCA：从 planner_plan 提取到报告，长度 {}", report.get().length());
                return report;
            }
            logger.warn("RCA：planner_plan 是计划 JSON 而非报告，兜底扫描内层状态");
        }
        Optional<String> fallback = scanStateForReport(state);
        fallback.ifPresentOrElse(
                r -> logger.info("RCA：从内层状态兜底提取到报告，长度 {}", r.length()),
                () -> logger.warn("RCA：内层状态里没有任何像报告的文本"));
        return fallback;
    }

    /**
     * 单段文本 → 报告：Markdown 原样返回；JSON（协议 A 的 decision 包裹）则抽报告字段。
     */
    static Optional<String> extractReportText(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String trimmed = text.strip();
        if (!looksLikeJson(trimmed)) {
            return Optional.of(trimmed);
        }
        String body = trimmed;
        if (body.startsWith("```")) {
            Matcher m = JSON_FENCE.matcher(body);
            if (m.find()) {
                body = m.group(1).strip();
            }
        }
        try {
            int start = body.indexOf('{');
            int end = body.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return Optional.empty();
            }
            JsonNode node = new ObjectMapper().readTree(body.substring(start, end + 1));
            for (String field : new String[]{"finalReport", "final_report", "report", "content", "markdown"}) {
                String value = node.path(field).asText("");
                if (!value.isBlank() && (value.contains(REPORT_TITLE) || value.length() > 100)) {
                    return Optional.of(value.strip());
                }
            }
        } catch (Exception ignored) {
            // 不是合法 JSON：可能是「JSON + Markdown」混排，交给兜底扫描
        }
        int idx = trimmed.indexOf("# " + REPORT_TITLE);
        if (idx >= 0) {
            return Optional.of(trimmed.substring(idx));
        }
        return Optional.empty();
    }

    private Optional<String> scanStateForReport(OverAllState state) {
        String best = null;
        for (Map.Entry<String, Object> entry : state.data().entrySet()) {
            for (String text : textsOf(entry.getValue())) {
                if (text == null || text.isBlank()) {
                    continue;
                }
                Optional<String> asReport = extractReportText(text);
                if (asReport.isEmpty()) {
                    continue;
                }
                String candidate = asReport.get();
                if (candidate.contains(REPORT_TITLE) || candidate.contains("告警处理详情")) {
                    return Optional.of(candidate);
                }
                if (candidate.length() >= 300 && (best == null || candidate.length() > best.length())) {
                    best = candidate;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static Optional<String> textOf(Object value) {
        if (value instanceof AssistantMessage msg) {
            return Optional.ofNullable(msg.getText());
        }
        if (value instanceof String s) {
            return Optional.of(s);
        }
        return Optional.empty();
    }

    private static List<String> textsOf(Object value) {
        List<String> texts = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof AssistantMessage msg) {
                    texts.add(msg.getText());
                }
            }
            return texts;
        }
        textOf(value).ifPresent(texts::add);
        return texts;
    }

    private static boolean looksLikeJson(String text) {
        String t = text.strip();
        return t.startsWith("{") || t.startsWith("```json") || t.startsWith("```\n{");
    }

    // ==================== 引擎 2：单个 ReactAgent ====================

    private String runReact(ChatModel chatModel, ToolCallback[] callbacks, String task) throws Exception {
        ReactAgent agent = ReactAgent.builder()
                .name("rca_agent")
                .description("根因分析：自主调用工具排查告警并输出报告")
                .model(chatModel)
                .systemPrompt(buildReactPrompt())
                .methodTools(dateTimeTools, internalDocsTools, queryMetricsTools, skillTools)
                .tools(callbacks)
                .build();
        AssistantMessage answer = agent.call(List.<Message>of(new UserMessage(task)));
        return answer == null || answer.getText() == null ? "" : answer.getText();
    }

    // ==================== 结论二次抽取（无工具） ====================

    /**
     * 报告尾部没有结论 JSON 时，用一次纯文本 LLM 调用把报告压成 {@link RcaConclusion}。
     * 只读报告，不再查工具，因此不会引入新证据；失败返回 null。
     */
    RcaConclusion extractConclusionFromReport(ChatModel chatModel, Incident incident, String report) {
        try {
            String system = """
                    你是结构化抽取器。只根据给定的《告警分析报告》输出一个 JSON 对象，不要输出任何解释、Markdown 或代码块围栏。
                    字段固定：root_cause(一句话), confidence(0~1 数字), root_component(用拓扑里的名字，无法判断填空串),
                    affected_alerts(字符串数组), evidence(字符串数组，摘自报告), adopted_exp_ids(报告中出现的「采用经验: <expId>」的 expId 数组，没有则 []),
                    proposed_playbooks(数组，元素 {playbook_id,target,reason}；playbook_id 只能来自给定目录，没有合适的则 [])。
                    confidence 规则：告警 + 日志 + 指标三者互相印证 0.85 以上；只有告警没有日志证据不超过 0.7；证据矛盾或工具全部失败不超过 0.4；报告写「未发现故障」则 root_cause 为空串、confidence 0。
                    """;
            StringBuilder user = new StringBuilder();
            user.append("【拓扑】根候选：").append(joinOrDash(incident.getRootComponents()))
                    .append("；症状：").append(joinOrDash(incident.getSymptomComponents()))
                    .append("；firing 告警：").append(joinOrDash(incident.getFiringNames())).append('\n');
            user.append(playbookCatalog()).append('\n');
            user.append("【报告】\n").append(report.length() > 12000 ? report.substring(0, 12000) : report);
            ChatResponse response = chatModel.call(new Prompt(List.of(new SystemMessage(system), new UserMessage(user.toString()))));
            String text = response == null || response.getResult() == null || response.getResult().getOutput() == null
                    ? "" : response.getResult().getOutput().getText();
            Parsed parsed = parse(text);
            return parsed.conclusion.isParsed() ? parsed.conclusion : null;
        } catch (Exception e) {
            logger.warn("RCA 结论二次抽取失败: {}", e.getMessage());
            return null;
        }
    }

    /** 提议的 playbook 只保留目录中存在的；置信度夹到 [0,1] */
    private void sanitizeConclusion(RcaConclusion conclusion) {
        if (conclusion == null) {
            return;
        }
        conclusion.setConfidence(Math.max(0.0, Math.min(1.0, conclusion.getConfidence())));
        List<RcaConclusion.ProposedPlaybook> kept = new ArrayList<>();
        for (RcaConclusion.ProposedPlaybook p : conclusion.getProposedPlaybooks()) {
            if (p != null && properties.findPlaybook(p.getPlaybookId()) != null) {
                kept.add(p);
            } else if (p != null) {
                logger.info("RCA 提议的 playbook 不在目录中，丢弃: {}", p.getPlaybookId());
            }
        }
        conclusion.setProposedPlaybooks(kept);
    }

    // ==================== 提示词 ====================

    /** 所有引擎共用的排查硬约束 */
    private static final String COMMON_RULES = """
            ## 排查规则
            1. 一键排障的主证据是 Prometheus firing 告警。任务里若已有【当前 Prometheus 告警快照】，把它当作已确认的 firing 告警清单；可再调用 queryPrometheusAlerts 复核。
            2. 【拓扑归并】已给出根候选与症状组件：优先围绕根候选定位根因，症状组件（因依赖故障而报错）不要当主因。
            3. 查询腾讯云 CLS 日志前必须先调用 loadSkill（name=cls-log-query），再严格按 skill 中的 MCP 顺序执行；SearchLog 前必须 TextToSearchLogQuery；禁止 level:ERROR 以及 UnknownHostException: 等「标识符:」字段检索（本环境无这些索引），改用全文 ERROR 或 "UnknownHostException"；同一条 CQL 报 not indexed / SyntaxError 后禁止原样重试。Region 不确定时省略以使用默认值。禁止对日志主题调用 QueryMetric（本环境无 CLS 指标主题，会报 the topic is not metric topic）；指标只用 queryPrometheusAlerts。
            4. 严格禁止编造数据，只能引用工具返回的真实内容。queryPrometheusAlerts 因连接重置失败 ≠ 无告警；CLS 近 15 分钟日志量为 0 ≠ 业务无故障，不能用来否定已经 firing 的告警。
            5. 历史经验仅供参考。与当前告警名称/实例不符的旧经验禁止当主因。处理建议优先恢复告警对应的中间件或进程，禁止把「重启全部微服务」当作首选。
            6. 若采纳任务中的某条历史经验，必须在对应「根因结论」中写明「采用经验: <expId>」，并把 expId 填进结论 JSON 的 adopted_exp_ids。
            7. 同一工具连续 3 次失败或返回空结果就停止该方向，但已注入的 firing 告警仍必须出现在报告的活跃告警清单与根因分析中。
            8. 区分「进程宕机」与「连接打满 / 参数问题」：mysql_up 仍为 1、进程仍在但日志出现 Too many connections 时，主因是连接耗尽而不是 MySQLDown。MySQLSlowQueries 时查 CLS 主题 mysql-slow（全文 Query_time / Rows_examined / SELECT）定位 SQL，不要只根据告警名判断，也不要 SSH 查库。
            9. 处置建议只能引用任务中【可用自愈 playbook 目录】里的 id；目录里没有合适项就写「无匹配 playbook，需人工处置」，不要自创命令。具体命令参数由后续决策节点按模板填写。
            """;

    /** 报告模板 + 尾部结论 JSON 契约（引擎共用） */
    private static final String REPORT_CONTRACT = """
            直接输出 Markdown 报告（不要输出成 JSON 对象，不要用 "finalReport": "..." 包裹，不要再带 decision 字段），严格遵循下列模板，从 "# 告警分析报告" 开始：

            ```
            # 告警分析报告

            ---

            ## 📋 活跃告警清单

            | 告警名称 | 级别 | 目标服务 | 首次触发时间 | 最新触发时间 | 状态 |
            |---------|------|----------|-------------|-------------|------|
            | [告警1名称] | [级别] | [服务名] | [时间] | [时间] | 活跃 |

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
            [基于证据得出的根本原因；采纳经验时写「采用经验: <expId>」]

            ---

            ## 🛠️ 处理方案执行1 - [告警名称]

            ### 已执行的排查步骤
            1. [步骤1]

            ### 处理建议
            [具体处理建议；可执行项写 playbook id]

            ### 预期效果
            [预期效果]

            ---

            ## 📊 结论

            ### 整体评估
            ### 关键发现
            ### 后续建议
            ### 风险评估
            ```

            报告结束后，**必须**再附一个 ```json 代码块（只允许一个，放在最后），字段固定如下：

            ```json
            {
              "root_cause": "一句话根因",
              "confidence": 0.0,
              "root_component": "组件名（用拓扑里的名字，如 mysql / redis / rabbitmq / course；无法判断填空串）",
              "affected_alerts": ["告警名1"],
              "evidence": ["关键证据1", "关键证据2"],
              "adopted_exp_ids": ["采纳的经验 expId，没有则为空数组"],
              "proposed_playbooks": [
                {"playbook_id": "只能填任务中 playbook 目录里的 id；没有合适的就留空数组", "target": "目标容器/实例", "reason": "为什么适用"}
              ]
            }
            ```

            confidence 取值 0~1：告警 + 日志 + 指标三者互相印证才给 0.85 以上；只有告警没有日志证据不超过 0.7；证据矛盾或工具全部失败不超过 0.4。
            """;

    private String buildPlannerPrompt() {
        return """
                你是 Planner Agent，同时承担 Replanner 角色，负责一键排障中的「根因分析」环节的规划与收尾。你的最终输出会交给后续的确定性程序去选择自愈 playbook、评估风险并执行，因此结论必须基于证据并结构化。

                ## 工作方式
                1. 读取当前输入任务 {input} 以及 Executor 的最近反馈 {executor_feedback}。
                2. 分析 Prometheus 告警、CLS 日志、内部文档、历史经验，制定可执行的下一步。一次只规划一步，交给 Executor 去查。
                3. 排查尚未结束时，输出 JSON：{"decision": "PLAN" 或 "EXECUTE", "step": "下一步做什么", "tools": ["预期调用的工具"], "context": "必要上下文"}。
                4. 证据足够、或同一方向已连续 3 次无收获时，进入 FINISH。

                """ + COMMON_RULES + """

                ## FINISH 时的输出要求（CRITICAL）
                decision=FINISH 时不要再输出调度 JSON，也不要输出 {"decision":"FINISH", ...}。
                """ + REPORT_CONTRACT;
    }

    private String buildExecutorPrompt() {
        return """
                你是 Executor Agent，负责读取 Planner 最新输出 {planner_plan}，只执行其中的第一步，并把证据整理给 Planner。
                - 确认步骤所需的工具与参数；Region 未给出时使用默认区域。
                - 调用相应的工具并收集结果。工具返回错误或空数据时，把失败原因、请求参数一并记录，并停止进一步调用该工具（同一工具失败达到 3 次直接返回 FAILED）。
                - 将日志、指标、文档等证据整理成结构化摘要，标注对应的告警名称、实例（如 3306）或组件（用拓扑里的名字），方便 Planner 填充「告警根因分析 / 处理方案执行」章节。
                - 若某条历史经验与当前证据吻合，在 summary 里写明「采用经验: <expId>」；不吻合就写明为何不采纳。
                - 以 JSON 形式返回执行状态、证据以及给 Planner 的建议，写入 executor_feedback，严禁编造未实际查询到的内容。

                """ + COMMON_RULES + """

                输出示例：
                {
                  "status": "SUCCESS",
                  "summary": "Prometheus firing MySQLDown，实例 192.168.150.101:3306；CLS 近 15 分钟无新日志，采集可能中断，不能据此否定告警",
                  "evidence": ["queryPrometheusAlerts: MySQLDown firing since ...", "SearchLog: 0 条"],
                  "component": "mysql",
                  "nextHint": "根候选 mysql 已确认宕机，可 FINISH；处置用 start-mysql-container"
                }
                """;
    }

    private String buildSupervisorPrompt() {
        return """
                你是 AI Ops Supervisor，负责调度 planner_agent 与 executor_agent：
                1. 当需要拆解任务或重新制定策略时，调用 planner_agent。
                2. 当 planner_agent 输出 decision=EXECUTE 时，调用 executor_agent 执行第一步。
                3. 根据 executor_agent 的反馈，评估是否需要再次调用 planner_agent，直到 planner_agent 输出《告警分析报告》（即 FINISH）。
                4. planner_agent 一旦输出以 "# 告警分析报告" 开头的 Markdown（尾部带结论 ```json 块），立即结束，不要再调用任何子 Agent，也不要改写或截断该报告。
                5. 若步骤涉及腾讯云 CLS 日志，确保 Executor 先 loadSkill（name=cls-log-query）再查日志，不要凭记忆跳过 skill。
                6. Prometheus firing 告警是一键排障的主目标。CLS 无新日志或 queryPrometheusAlerts 连接失败，都不能当成「没有故障 / 环境停机」而结束。
                7. 优先围绕任务中【拓扑归并】给出的根候选组件排查；症状组件不要当主因。
                8. 如果发现 Planner / Executor 在同一方向连续 3 次调用工具仍失败或没有数据，可以终止该方向，但仍须让 planner_agent 基于任务中的告警快照输出《告警分析报告》，写明告警名、实例和优先恢复对应组件；严禁凭空编造日志原文。

                只允许在 planner_agent、executor_agent 与 FINISH 之间做出选择。
                """;
    }

    private String buildReactPrompt() {
        return """
                你是企业级 SRE 的根因分析 Agent，负责一键排障中的「根因分析」环节。你的输出会交给后续的确定性程序去选择自愈 playbook、评估风险并执行，因此结论必须基于证据并结构化。

                """ + COMMON_RULES + """

                ## 输出要求（CRITICAL）
                完成排查后，""" + REPORT_CONTRACT;
    }

    private String buildTask(Incident incident, String experienceBlock) {
        StringBuilder task = new StringBuilder();
        if (incident.getSummary() != null && !incident.getSummary().isBlank()) {
            task.append(incident.getSummary()).append('\n');
        }
        if (experienceBlock != null && !experienceBlock.isBlank()) {
            task.append(experienceBlock).append('\n');
        }
        task.append(playbookCatalog()).append('\n');
        task.append("你接到了自动化告警排查任务。请结合工具调用，执行**规划→执行→再规划**的闭环完成排查，")
                .append("最终按系统提示词中的模板输出《告警分析报告》，并在最后附上唯一的 ```json 结论块。");
        task.append("优先围绕当前 firing 告警与根候选组件定位根因；历史经验仅供参考，必须用当前告警/日志验证后再采纳。");
        task.append("禁止编造虚假数据；Prometheus 连接失败 ≠ 没有告警；CLS 日志量为 0 ≠ 业务无故障。");
        return task.toString();
    }

    private String playbookCatalog() {
        StringBuilder sb = new StringBuilder("【可用自愈 playbook 目录】proposed_playbooks 只能从这里选 id，不适用则留空：\n");
        List<Playbook> playbooks = properties.getPlaybooks();
        if (playbooks.isEmpty()) {
            sb.append("（目录为空）\n");
        }
        for (Playbook p : playbooks) {
            sb.append("- ").append(p.getId()).append("：").append(p.getTitle())
                    .append("；组件=").append(p.getComponent())
                    .append("；适用告警=").append(p.getMatchAlerts().isEmpty() ? "-" : String.join("/", p.getMatchAlerts()))
                    .append("；风险=").append(p.getRisk());
            if (!p.getMatchKeywords().isEmpty()) {
                sb.append("；关键词=").append(String.join("/", p.getMatchKeywords()));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ==================== 解析 ====================

    record Parsed(String report, RcaConclusion conclusion) {
    }

    /**
     * 把 RCA 原始输出拆成「报告正文」+「结论」：
     * <ul>
     *   <li>含 root_cause 的 ```json 块 → 结论（取最后一个）；</li>
     *   <li>含 decision 而不含 root_cause 的 ```json 块（Planner 残留的调度 JSON）→ 当噪音从正文删掉；</li>
     *   <li>没有围栏但有裸 {"root_cause": ...} → 按括号配对切出；</li>
     *   <li>整段就是 {"decision":"FINISH","finalReport":"..."} → 先拆报告字段再递归解析。</li>
     * </ul>
     */
    Parsed parse(String raw) {
        RcaConclusion conclusion = new RcaConclusion();
        String text = raw == null ? "" : raw.strip();
        if (text.isEmpty()) {
            conclusion.setParseNote("empty answer");
            return new Parsed("", conclusion);
        }

        if (looksLikeJson(text) && !text.contains("\"root_cause\"") && !text.contains("\"rootCause\"")) {
            Optional<String> unwrapped = extractReportText(text);
            if (unwrapped.isPresent() && !unwrapped.get().equals(text)) {
                return parse(unwrapped.get());
            }
        }

        String jsonCandidate = null;
        StringBuilder report = new StringBuilder();
        int cursor = 0;
        Matcher m = JSON_FENCE.matcher(text);
        while (m.find()) {
            String body = m.group(1).strip();
            boolean isConclusion = body.contains("\"root_cause\"") || body.contains("\"rootCause\"");
            boolean isDecisionNoise = !isConclusion && body.startsWith("{") && body.contains("\"decision\"");
            if (isConclusion || isDecisionNoise) {
                report.append(text, cursor, m.start());
                cursor = m.end();
                if (isConclusion) {
                    jsonCandidate = body;
                }
            }
        }
        report.append(text.substring(cursor));
        String reportText = report.toString().strip();

        if (jsonCandidate == null) {
            int idx = reportText.lastIndexOf("\"root_cause\"");
            if (idx < 0) {
                idx = reportText.lastIndexOf("\"rootCause\"");
            }
            if (idx >= 0) {
                int open = reportText.lastIndexOf('{', idx);
                int close = matchBrace(reportText, open);
                if (open >= 0 && close > open) {
                    jsonCandidate = reportText.substring(open, close + 1);
                    reportText = (reportText.substring(0, open) + reportText.substring(close + 1)).strip();
                }
            }
        }

        if (jsonCandidate == null) {
            conclusion.setParseNote("no json block");
            return new Parsed(stripTrailingFenceNoise(reportText), conclusion);
        }
        try {
            RcaConclusion parsedConclusion = objectMapper.readValue(jsonCandidate, RcaConclusion.class);
            parsedConclusion.setParsed(true);
            parsedConclusion.setConfidence(Math.max(0.0, Math.min(1.0, parsedConclusion.getConfidence())));
            return new Parsed(stripTrailingFenceNoise(reportText), parsedConclusion);
        } catch (Exception e) {
            logger.warn("RCA 结论 JSON 解析失败: {}", e.getMessage());
            conclusion.setParseNote("json parse error: " + e.getMessage());
            return new Parsed(stripTrailingFenceNoise(reportText), conclusion);
        }
    }

    private static int matchBrace(String text, int open) {
        if (open < 0) {
            return -1;
        }
        int depth = 0;
        boolean inString = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 去掉模型偶尔留下的孤立 ``` 或「以下是结构化结论」之类尾巴 */
    private static String stripTrailingFenceNoise(String report) {
        String r = report.strip();
        while (r.endsWith("```")) {
            r = r.substring(0, r.length() - 3).strip();
        }
        return r;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }
}
