package org.example.aiops.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlackboardRoundTripTest {

    @Test
    void roundTripsTypedKeys() {
        Incident incident = new Incident();
        incident.setId("inc-1");
        incident.getFiringNames().add("MySQLDown");
        incident.getRootComponents().add("mysql");

        RcaConclusion conclusion = new RcaConclusion();
        conclusion.setParsed(true);
        conclusion.setRootCause("容器退出");
        conclusion.setConfidence(0.88);

        String json = Blackboard.toJson(Map.of(
                Blackboard.INCIDENT_ID, "inc-1",
                Blackboard.INCIDENT, incident,
                Blackboard.RCA_CONCLUSION, conclusion,
                Blackboard.REPORT_MARKDOWN, "# 告警分析报告"));

        Map<String, Object> restored = Blackboard.fromJson(json);
        Incident back = (Incident) restored.get(Blackboard.INCIDENT);
        RcaConclusion c2 = (RcaConclusion) restored.get(Blackboard.RCA_CONCLUSION);
        assertEquals("inc-1", back.getId());
        assertEquals("MySQLDown", back.getFiringNames().get(0));
        assertTrue(c2.isParsed());
        assertEquals(0.88, c2.getConfidence());
        assertEquals("# 告警分析报告", restored.get(Blackboard.REPORT_MARKDOWN));
    }
}
