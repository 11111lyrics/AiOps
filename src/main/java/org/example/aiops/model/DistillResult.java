package org.example.aiops.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 节点 ⑦ 沉淀的产物。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DistillResult {

    /** 报告是否覆盖了当前 firing 告警名（否则只做负反馈、不沉淀） */
    private boolean grounded;
    private List<String> adoptedExpIds = new ArrayList<>();
    private String storedExpId = "";
    private String note = "";
}
