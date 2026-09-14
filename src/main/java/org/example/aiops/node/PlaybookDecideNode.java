package org.example.aiops.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.aiops.IncidentProgressBus;
import org.example.aiops.IncidentRepository;
import org.example.aiops.PlaybookTemplates;
import org.example.aiops.config.AiOpsOrchestrationProperties;
import org.example.aiops.config.AiOpsOrchestrationProperties.Playbook;
import org.example.aiops.config.AiOpsOrchestrationProperties.PlaybookParam;
import org.example.aiops.model.Blackboard;
import org.example.aiops.model.Incident;
import org.example.aiops.model.PlaybookDecision;
import org.example.aiops.model.RcaConclusion;
import org.example.config.ChatModelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点 ③：处置决策。LLM 只能从 playbook 目录选一条模板，并填写 {param}；禁止自创命令。
 */
@Component
public class PlaybookDecideNode extends AbstractOpsNode {

    public static final String NAME = "decide";

    private static final Logger logger = LoggerFactory.getLogger(PlaybookDecideNode.class);

    private static final String SYSTEM = """
            你是一键排障的处置决策器。只能从给定的 playbook 目录里选一条，并用目录声明的 params.choices（或 default）填写占位符。
            只输出一个 JSON 对象，不要解释、不要 Markdown 围栏。
            字段：
            - no_action: 目录里没有合适项时为 true，此时 playbook_id 留空
            - playbook_id: 必须是目录中的 id
            - params: 对象，key 必须是该 playbook 声明的参数名，value 必须落在对应 choices 内（无 choices 才可自填，且只能是字母数字._:-）
            - reason: 一句话说明为什么选这条、参数怎么定
            禁止输出 commands 字段，禁止发明目录外的命令或改写句式。
            若有【人工建议】，优先按建议在目录内改选 playbook 或改参数；建议无法用现有 playbook 落地时 no_action=true。
            """;

    private final AiOpsOrchestrationProperties properties;
    private final ChatModelFactory chatModelFactory;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public PlaybookDecideNode(IncidentProgressBus bus, IncidentRepository repository,
                              AiOpsOrchestrationProperties properties, ChatModelFactory chatModelFactory) {
        super(bus, repository);
        this.properties = properties;
        this.chatModelFactory = chatModelFactory;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    protected void run(OverAllState state, Map<String, Object> out) {
        String suggestion = Blackboard.humanSuggestion(state);
        boolean revise = suggestion != null && !suggestion.isBlank();
        PlaybookDecision decision = new PlaybookDecision();
        decision.setHumanSuggestion(suggestion == null ? "" : suggestion);
        decision.setSelectedBy(revise ? "llm_revise" : "llm");

        if (properties.getPlaybooks().isEmpty()) {
            decision.setEmpty(true);
            decision.setReason("playbook 目录为空");
            finish(state, out, decision, "【③ 处置决策】" + decision.getReason());
            return;
        }

        progress(state, out, revise
                ? "【③ 处置决策】已收到人工建议，正在结合建议重新选择 playbook 并填写参数…"
                : "【③ 处置决策】正在根据报告从目录中选择命令模板并填写参数…");

        try {
            ChatModel model = chatModelFactory.create(Blackboard.provider(state), 0.2, 2000, 0.9);
            ChatResponse response = model.call(new Prompt(List.of(
                    new SystemMessage(SYSTEM),
                    new UserMessage(buildUserPrompt(state, suggestion)))));
            String raw = textOf(response);
            JsonNode node = parseJson(raw);
            if (node == null) {
                throw new IllegalStateException("决策输出不是合法 JSON");
            }
            if (node.path("no_action").asBoolean(false)) {
                decision.setEmpty(true);
                decision.setReason(node.path("reason").asText("目录中无匹配 playbook"));
                finish(state, out, decision, "【③ 处置决策】不执行：" + decision.getReason());
                return;
            }
            applyLlmChoice(decision, node);
        } catch (Exception e) {
            logger.warn("处置决策 LLM 失败，交给规划节点兜底: {}", e.getMessage());
            decision.setEmpty(true);
            decision.setReason("决策失败，将回退查表：" + e.getMessage());
            decision.setSelectedBy("fallback");
            finish(state, out, decision, "【③ 处置决策】" + decision.getReason());
            return;
        }

        finish(state, out, decision, "【③ 处置决策】选中 " + decision.getPlaybookId()
                + "，参数 " + decision.getParams()
                + "。依据：" + decision.getReason());
    }

    private void applyLlmChoice(PlaybookDecision decision, JsonNode node) {
        String playbookId = node.path("playbook_id").asText("").trim();
        Playbook playbook = properties.findPlaybook(playbookId);
        if (playbook == null) {
            throw new IllegalStateException("playbook 不在目录中: " + playbookId);
        }
        Map<String, String> rawParams = new LinkedHashMap<>();
        JsonNode paramsNode = node.path("params");
        if (paramsNode.isObject()) {
            paramsNode.fields().forEachRemaining(entry -> {
                if (entry.getValue() != null && entry.getValue().isValueNode()) {
                    rawParams.put(entry.getKey(), entry.getValue().asText(""));
                }
            });
        }
        Map<String, String> resolved = PlaybookTemplates.resolveParams(playbook, rawParams);
        decision.setPlaybookId(playbook.getId());
        decision.setParams(resolved);
        decision.setCommands(PlaybookTemplates.fillAll(playbook.getCommands(), resolved));
        decision.setReason(node.path("reason").asText(""));
        decision.setEmpty(false);
    }

    private String buildUserPrompt(OverAllState state, String suggestion) {
        Incident incident = Blackboard.incident(state).orElseGet(Incident::new);
        RcaConclusion conclusion = Blackboard.conclusion(state).orElseGet(RcaConclusion::new);
        StringBuilder sb = new StringBuilder();
        sb.append("【告警】firing=").append(joinOrDash(incident.getFiringNames()))
                .append("；根候选=").append(joinOrDash(incident.getRootComponents()))
                .append("；症状=").append(joinOrDash(incident.getSymptomComponents())).append('\n');
        sb.append("【RCA 根因】").append(conclusion.getRootCause())
                .append("（置信度 ").append(String.format("%.2f", conclusion.getConfidence())).append("）\n");
        if (!conclusion.getProposedPlaybooks().isEmpty()) {
            sb.append("【RCA 建议 playbook】");
            conclusion.getProposedPlaybooks().forEach(p ->
                    sb.append(p.getPlaybookId()).append('(').append(p.getReason()).append(") "));
            sb.append('\n');
        }
        String report = Blackboard.report(state);
        if (report != null && !report.isBlank()) {
            sb.append("【报告摘要】\n").append(report.length() > 4000 ? report.substring(0, 4000) : report).append("\n\n");
        }
        if (suggestion != null && !suggestion.isBlank()) {
            sb.append("【人工建议】").append(suggestion.strip()).append("\n\n");
        }
        sb.append("【playbook 目录】\n").append(catalog());
        return sb.toString();
    }

    private String catalog() {
        StringBuilder sb = new StringBuilder();
        for (Playbook playbook : properties.getPlaybooks()) {
            sb.append("- id=").append(playbook.getId())
                    .append("；title=").append(playbook.getTitle())
                    .append("；component=").append(playbook.getComponent())
                    .append("；risk=").append(playbook.getRisk())
                    .append("；alerts=").append(playbook.getMatchAlerts())
                    .append("；keywords=").append(playbook.getMatchKeywords()).append('\n');
            sb.append("  命令模板：").append(playbook.getCommands()).append('\n');
            if (playbook.getParams() != null && !playbook.getParams().isEmpty()) {
                sb.append("  参数：");
                for (Map.Entry<String, PlaybookParam> entry : playbook.getParams().entrySet()) {
                    PlaybookParam spec = entry.getValue();
                    sb.append(entry.getKey()).append("{");
                    if (spec.getDescription() != null && !spec.getDescription().isBlank()) {
                        sb.append(spec.getDescription()).append("; ");
                    }
                    if (spec.getChoices() != null && !spec.getChoices().isEmpty()) {
                        sb.append("choices=").append(spec.getChoices());
                    }
                    if (spec.getDefaultValue() != null && !spec.getDefaultValue().isBlank()) {
                        sb.append(" default=").append(spec.getDefaultValue());
                    }
                    sb.append("} ");
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private void finish(OverAllState state, Map<String, Object> out, PlaybookDecision decision, String line) {
        out.put(Blackboard.PLAYBOOK_DECISION, decision);
        audit(state, "decision", Blackboard.toJsonQuietly(decision));
        progress(state, out, line);
        done(state, line);
    }

    private JsonNode parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.strip();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return objectMapper.readTree(text.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static String textOf(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }
}
