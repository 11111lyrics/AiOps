package org.example.aiops.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 节点 ② 根因分析的结构化结论（由 RCA Agent 报告尾部的 JSON 块解析而来）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RcaConclusion {

    @JsonProperty("root_cause")
    @JsonAlias({"rootCause"})
    private String rootCause = "";

    private double confidence;

    @JsonProperty("root_component")
    @JsonAlias({"rootComponent"})
    private String rootComponent = "";

    @JsonProperty("affected_alerts")
    @JsonAlias({"affectedAlerts"})
    private List<String> affectedAlerts = new ArrayList<>();

    private List<String> evidence = new ArrayList<>();

    @JsonProperty("adopted_exp_ids")
    @JsonAlias({"adoptedExpIds"})
    private List<String> adoptedExpIds = new ArrayList<>();

    @JsonProperty("proposed_playbooks")
    @JsonAlias({"proposedPlaybooks"})
    private List<ProposedPlaybook> proposedPlaybooks = new ArrayList<>();

    /** 结论 JSON 是否成功解析；false 时上面字段为默认值 */
    private boolean parsed;
    private String parseNote = "";

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProposedPlaybook {
        @JsonProperty("playbook_id")
        @JsonAlias({"playbookId", "id"})
        private String playbookId = "";
        private String target = "";
        private String reason = "";
    }
}
