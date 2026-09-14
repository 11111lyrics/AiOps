package org.example.aiops.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.tool.QueryMetricsTools;
import org.example.agent.tool.QueryMetricsTools.SimplifiedAlert;
import org.example.aiops.IncidentProgressBus;
import org.example.aiops.IncidentRepository;
import org.example.aiops.TopologyResolver;
import org.example.aiops.model.Blackboard;
import org.example.aiops.model.Incident;
import org.example.service.ExperienceService;
import org.example.service.ExperienceService.RecalledExperience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 节点 ①：告警接入。拉 Prometheus 快照、去重、按拓扑归并根候选 / 症状、预召回经验。确定性代码，不调 LLM。
 */
@Component
public class IncidentIntakeNode extends AbstractOpsNode {

    public static final String NAME = "intake";

    private static final Logger logger = LoggerFactory.getLogger(IncidentIntakeNode.class);

    private final QueryMetricsTools queryMetricsTools;
    private final TopologyResolver topology;
    private final ExperienceService experienceService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IncidentIntakeNode(IncidentProgressBus bus, IncidentRepository repository,
                              QueryMetricsTools queryMetricsTools, TopologyResolver topology,
                              ExperienceService experienceService) {
        super(bus, repository);
        this.queryMetricsTools = queryMetricsTools;
        this.topology = topology;
        this.experienceService = experienceService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    protected void run(OverAllState state, Map<String, Object> out) {
        String incidentId = Blackboard.incidentId(state);
        Incident incident = new Incident();
        incident.setId(incidentId);
        incident.setCreatedAt(System.currentTimeMillis());

        String snapshot = queryMetricsTools.queryPrometheusAlerts();
        incident.setSnapshotOk(snapshotSucceeded(snapshot));
        if (!incident.isSnapshotOk()) {
            incident.setSnapshotError(extractError(snapshot));
        }

        List<SimplifiedAlert> firing = queryMetricsTools.extractFiringAlerts(snapshot);
        incident.setAlerts(firing);
        incident.setFiringNames(queryMetricsTools.extractFiringNames(snapshot));

        Set<String> alerted = new LinkedHashSet<>();
        for (SimplifiedAlert alert : firing) {
            String comp = topology.resolveComponent(alert);
            if (!comp.isEmpty()) {
                alerted.add(comp);
            }
        }
        incident.setAlertedComponents(new ArrayList<>(alerted));
        List<String> roots = topology.rootCandidates(alerted);
        incident.setRootComponents(roots);
        List<String> symptoms = new ArrayList<>(alerted);
        symptoms.removeAll(roots);
        incident.setSymptomComponents(symptoms);

        String recallQuery = queryMetricsTools.toExperienceRecallQuery(snapshot);
        incident.setRecallQuery(recallQuery);
        List<RecalledExperience> recalled = new ArrayList<>();
        String experienceBlock = "";
        try {
            recalled = experienceService.recall(recallQuery, experienceService.extractEnvHint(recallQuery));
            experienceBlock = experienceService.formatExperienceBlock(recalled);
        } catch (Exception e) {
            logger.warn("经验预召回失败（继续排障）: {}", e.getMessage());
        }

        incident.setSummary(buildSummary(incident, snapshot));

        out.put(Blackboard.INCIDENT, incident);
        out.put(Blackboard.RECALLED_EXPERIENCE, recalled);
        out.put(Blackboard.EXPERIENCE_BLOCK, experienceBlock == null ? "" : experienceBlock);

        repository.updateSummary(incidentId, String.join(",", incident.getFiringNames()), String.join(",", roots));
        audit(state, "snapshot", snapshot);

        StringBuilder line = new StringBuilder("【① 告警接入】");
        if (!incident.isSnapshotOk()) {
            line.append("Prometheus 快照拉取失败（").append(incident.getSnapshotError()).append("），");
        }
        line.append("firing 告警 ").append(firing.size()).append(" 条：").append(joinOrDash(incident.getFiringNames()));
        if (!alerted.isEmpty()) {
            line.append("；根候选：").append(joinOrDash(roots));
            if (!symptoms.isEmpty()) {
                line.append("；症状：").append(joinOrDash(symptoms));
            }
        }
        line.append("；预召回经验 ").append(recalled.size()).append(" 条");
        progress(state, out, line.toString());
        done(state, line.toString());
    }

    private boolean snapshotSucceeded(String snapshot) {
        try {
            JsonNode node = objectMapper.readTree(snapshot);
            return node.path("success").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    private String extractError(String snapshot) {
        try {
            JsonNode node = objectMapper.readTree(snapshot);
            String err = node.path("error").asText("");
            return err.isBlank() ? node.path("message").asText("unknown") : err;
        } catch (Exception e) {
            return "unparseable snapshot";
        }
    }

    private String buildSummary(Incident incident, String snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("【当前 Prometheus 告警快照】系统在启动一键排障时拉取，执行中仍须用 queryPrometheusAlerts 复核。\n");
        sb.append("若工具调用因连接重置失败，不得据此写成「当前无告警」；有 firing 告警时以本快照继续排查。\n");
        sb.append(snapshot).append("\n\n");
        sb.append("【拓扑归并】告警组件：").append(joinOrDash(incident.getAlertedComponents()))
                .append("；根候选（依赖链上无其它告警）：").append(joinOrDash(incident.getRootComponents()))
                .append("；症状（其依赖也在告警）：").append(joinOrDash(incident.getSymptomComponents())).append("\n");
        if (incident.getFiringNames().isEmpty()) {
            sb.append("当前没有 firing 告警：请重点查 CLS 日志里的 ERROR（如 Too many connections、Connection refused），")
                    .append("判断是否存在监控未覆盖的故障；确实无异常则如实写「未发现故障」。\n");
        }
        return sb.toString();
    }
}
