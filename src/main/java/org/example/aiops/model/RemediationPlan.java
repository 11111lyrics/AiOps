package org.example.aiops.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点 ③ 处置规划的产物：从 playbook 目录选出的一条可执行计划及其 dry-run。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RemediationPlan {

    /** 没有可用 playbook 时为 true，reason 说明原因 */
    private boolean empty;
    private String reason = "";

    private String playbookId = "";
    private String title = "";
    private String component = "";
    private String target = "";
    private String baseRisk = "L2";
    private List<String> commands = new ArrayList<>();
    private String rollback = "";
    private String expectedEffect = "";
    private String verifyCommand = "";
    private String verifyExpect = "";

    /** 前置检查：checked=false 表示未执行（SSH 未启用） */
    private boolean preconditionChecked;
    private boolean preconditionOk = true;
    private String preconditionCommand = "";
    private String preconditionOutput = "";

    /** llm / llm_revise / rca_proposed / match_alert / match_keyword / fallback */
    private String selectedBy = "";
    /** 人可读的 dry-run 文本 */
    private String dryRun = "";
    /** 决策 LLM 填入的占位符 */
    private Map<String, String> params = new LinkedHashMap<>();
    private String decisionReason = "";
    private String humanSuggestion = "";
}
