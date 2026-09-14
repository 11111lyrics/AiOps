package org.example.aiops.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import org.example.agent.tool.QueryMetricsTools.SimplifiedAlert;

import java.util.ArrayList;
import java.util.List;

/**
 * 节点 ① 告警接入的产物：去重后的 firing 告警 + 按依赖拓扑归并出的根候选 / 症状组件。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Incident {

    private String id;
    private long createdAt;
    /** 告警快照是否成功拉取（false 时 alerts 为空且 snapshotError 有值） */
    private boolean snapshotOk;
    private String snapshotError;
    private List<SimplifiedAlert> alerts = new ArrayList<>();
    private List<String> firingNames = new ArrayList<>();
    /** 有告警的组件（拓扑归一化后的名字） */
    private List<String> alertedComponents = new ArrayList<>();
    /** 根候选：有告警且其依赖链上没有其它告警组件 */
    private List<String> rootComponents = new ArrayList<>();
    /** 症状组件：有告警但其依赖也在告警 */
    private List<String> symptomComponents = new ArrayList<>();
    /** 经验预召回用的查询词 */
    private String recallQuery;
    /** 给 RCA 提示词的一段人可读摘要 */
    private String summary;
}
