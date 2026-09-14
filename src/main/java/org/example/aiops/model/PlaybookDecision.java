package org.example.aiops.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点 ③ 处置决策：LLM 从目录里选一条 playbook，并填好命令模板占位符。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlaybookDecision {

    private boolean empty;
    private String reason = "";
    private String playbookId = "";
    private Map<String, String> params = new LinkedHashMap<>();
    private List<String> commands = new ArrayList<>();
    /** llm / llm_revise / fallback */
    private String selectedBy = "";
    private String humanSuggestion = "";
}
