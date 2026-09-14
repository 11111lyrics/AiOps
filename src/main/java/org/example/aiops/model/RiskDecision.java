package org.example.aiops.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 节点 ④ 风险分级的产物。decision 决定条件边走向。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RiskDecision {

    public static final String AUTO_EXECUTE = "AUTO_EXECUTE";
    public static final String PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String NO_ACTION = "NO_ACTION";

    /** L0 / L1 / L2 */
    private String level = "L2";
    private int score;
    private String decision = NO_ACTION;
    private List<String> reasons = new ArrayList<>();
    /** 熔断器是否打开（同 playbook + 目标近 1 小时执行次数达上限） */
    private boolean circuitOpen;

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public List<String> getReasons() {
        return reasons;
    }

    public void setReasons(List<String> reasons) {
        this.reasons = reasons == null ? new ArrayList<>() : reasons;
    }

    public boolean isCircuitOpen() {
        return circuitOpen;
    }

    public void setCircuitOpen(boolean circuitOpen) {
        this.circuitOpen = circuitOpen;
    }
}
