package org.example.aiops;

import org.example.agent.tool.QueryMetricsTools.SimplifiedAlert;
import org.example.aiops.config.AiOpsOrchestrationProperties;
import org.example.aiops.config.AiOpsOrchestrationProperties.ComponentDef;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 服务依赖拓扑：把告警映射到组件，并区分「根候选」与「症状」。
 * 组件 c 若其（传递）依赖中有任一组件也在告警，则 c 只是症状；否则为根候选。
 */
@Component
public class TopologyResolver {

    private final AiOpsOrchestrationProperties properties;

    public TopologyResolver(AiOpsOrchestrationProperties properties) {
        this.properties = properties;
    }

    /**
     * 告警 → 组件名；识别不出返回空串。
     * 顺序：alertname 精确映射 → service/job 命中组件名或别名 → alertname/描述/instance 关键词。
     */
    public String resolveComponent(SimplifiedAlert alert) {
        if (alert == null) {
            return "";
        }
        Map<String, String> alertComponents = properties.getTopology().getAlertComponents();
        String alertName = safe(alert.getAlertName());
        for (Map.Entry<String, String> entry : alertComponents.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(alertName)) {
                String comp = normalize(entry.getValue());
                // ServiceUnavailable 这类通用告警：优先用 label 里的服务名再细化
                String byLabel = matchByText(safe(alert.getService()) + " " + safe(alert.getInstance())
                        + " " + safe(alert.getDescription()));
                return byLabel.isEmpty() ? comp : byLabel;
            }
        }
        String byLabel = matchByText(safe(alert.getService()) + " " + safe(alert.getInstance()));
        if (!byLabel.isEmpty()) {
            return byLabel;
        }
        return matchByText(alertName + " " + safe(alert.getDescription()));
    }

    /**
     * 在文本里找组件名或别名（按 token 匹配，避免 "cs" 误命中长单词）。
     */
    public String matchByText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        Set<String> tokens = new HashSet<>();
        for (String t : lower.split("[^a-z0-9_\\-]+")) {
            if (!t.isBlank()) {
                tokens.add(t);
            }
        }
        for (Map.Entry<String, ComponentDef> entry : properties.getTopology().getComponents().entrySet()) {
            String name = normalize(entry.getKey());
            if (tokens.contains(name) || lower.contains(name + "-service") || lower.contains("tj-" + name)) {
                return name;
            }
            for (String alias : entry.getValue().getAliases()) {
                String a = normalize(alias);
                if (a.isEmpty()) {
                    continue;
                }
                if (a.matches("\\d+")) {
                    if (lower.contains(":" + a)) {
                        return name;
                    }
                } else if (tokens.contains(a) || lower.contains(a)) {
                    return name;
                }
            }
        }
        return "";
    }

    /**
     * 根候选：有告警且其依赖链上没有其它告警组件。
     */
    public List<String> rootCandidates(Set<String> alerted) {
        List<String> roots = new ArrayList<>();
        for (String comp : alerted) {
            Set<String> deps = transitiveDependencies(comp);
            boolean hasAlertedDep = false;
            for (String d : deps) {
                if (alerted.contains(d)) {
                    hasAlertedDep = true;
                    break;
                }
            }
            if (!hasAlertedDep) {
                roots.add(comp);
            }
        }
        return roots;
    }

    public String criticality(String component) {
        ComponentDef def = properties.getTopology().getComponents().get(normalize(component));
        return def == null ? "medium" : safe(def.getCriticality()).toLowerCase(Locale.ROOT);
    }

    private Set<String> transitiveDependencies(String component) {
        Set<String> visited = new LinkedHashSet<>();
        List<String> stack = new ArrayList<>();
        stack.add(normalize(component));
        while (!stack.isEmpty()) {
            String current = stack.remove(stack.size() - 1);
            ComponentDef def = properties.getTopology().getComponents().get(current);
            if (def == null) {
                continue;
            }
            for (String dep : def.getDependsOn()) {
                String d = normalize(dep);
                if (!d.isEmpty() && visited.add(d)) {
                    stack.add(d);
                }
            }
        }
        return visited;
    }

    public static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
