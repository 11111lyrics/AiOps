package org.example.aiops.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 节点 ⑥ 验证的产物。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class VerificationResult {

    /** prometheus / command / none / skipped */
    private String method = "none";
    private boolean resolved;
    private long elapsedSeconds;
    private int polls;
    private List<String> remainingAlerts = new ArrayList<>();
    private String detail = "";
}
