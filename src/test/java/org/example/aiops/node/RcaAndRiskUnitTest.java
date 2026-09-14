package org.example.aiops.node;

import org.example.aiops.model.RcaConclusion;
import org.example.aiops.model.RiskDecision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RcaAndRiskUnitTest {

    @Test
    void parsesJsonFenceFromReport() {
        RootCauseAnalysisNode node = new RootCauseAnalysisNode(
                null, null, null, null, null, null, null, null, null);
        String raw = """
                # 告警分析报告

                根因是 MySQL 容器被 stop。

                ```json
                {"root_cause":"mysql 容器退出","confidence":0.9,"root_component":"mysql","proposed_playbooks":[{"playbook_id":"start-mysql-container"}]}
                ```
                """;
        RootCauseAnalysisNode.Parsed parsed = node.parse(raw);
        assertTrue(parsed.conclusion().isParsed());
        assertEquals("mysql 容器退出", parsed.conclusion().getRootCause());
        assertEquals("mysql", parsed.conclusion().getRootComponent());
        assertEquals("start-mysql-container", parsed.conclusion().getProposedPlaybooks().get(0).getPlaybookId());
        assertTrue(parsed.report().contains("# 告警分析报告"));
        assertFalse(parsed.report().contains("root_cause"));
    }

    @Test
    void missingJsonKeepsReport() {
        RootCauseAnalysisNode node = new RootCauseAnalysisNode(
                null, null, null, null, null, null, null, null, null);
        RootCauseAnalysisNode.Parsed parsed = node.parse("# 告警分析报告\n\n没有结构化结论");
        assertFalse(parsed.conclusion().isParsed());
        assertTrue(parsed.report().contains("没有结构化结论"));
    }

    @Test
    void stripsPlannerDecisionJsonNoiseButKeepsConclusion() {
        RootCauseAnalysisNode node = new RootCauseAnalysisNode(
                null, null, null, null, null, null, null, null, null);
        String raw = """
                ```json
                {"decision":"FINISH","step":"输出报告"}
                ```
                # 告警分析报告

                ## 根因结论
                redis 容器 exited。

                ```json
                {"root_cause":"redis 容器退出","confidence":0.8,"root_component":"redis"}
                ```
                """;
        RootCauseAnalysisNode.Parsed parsed = node.parse(raw);
        assertTrue(parsed.conclusion().isParsed());
        assertEquals("redis", parsed.conclusion().getRootComponent());
        assertFalse(parsed.report().contains("\"decision\""));
        assertTrue(parsed.report().startsWith("# 告警分析报告"));
        assertTrue(parsed.report().contains("redis 容器 exited"));
    }

    @Test
    void unwrapsFinishJsonWrappingTheReport() {
        String wrapped = "{\"decision\":\"FINISH\",\"finalReport\":\"# 告警分析报告\\n\\n"
                + "MySQL 容器停止。\\n\\n```json\\n{\\\"root_cause\\\":\\\"mysql 停止\\\",\\\"confidence\\\":0.9,\\\"root_component\\\":\\\"mysql\\\"}\\n```\"}";
        assertTrue(RootCauseAnalysisNode.extractReportText(wrapped).orElse("").startsWith("# 告警分析报告"));

        RootCauseAnalysisNode node = new RootCauseAnalysisNode(
                null, null, null, null, null, null, null, null, null);
        RootCauseAnalysisNode.Parsed parsed = node.parse(wrapped);
        assertTrue(parsed.conclusion().isParsed());
        assertEquals("mysql", parsed.conclusion().getRootComponent());
        assertTrue(parsed.report().contains("MySQL 容器停止"));
        assertFalse(parsed.report().contains("finalReport"));
    }

    @Test
    void bareConclusionJsonFromExtractorIsParsed() {
        RootCauseAnalysisNode node = new RootCauseAnalysisNode(
                null, null, null, null, null, null, null, null, null);
        RootCauseAnalysisNode.Parsed parsed = node.parse(
                "{\"root_cause\":\"连接打满\",\"confidence\":1.7,\"root_component\":\"mysql\",\"proposed_playbooks\":[{\"playbook_id\":\"mysql-raise-max-connections\"}]}");
        assertTrue(parsed.conclusion().isParsed());
        assertEquals(1.0, parsed.conclusion().getConfidence());
        assertEquals("mysql-raise-max-connections", parsed.conclusion().getProposedPlaybooks().get(0).getPlaybookId());
    }

    @Test
    void markdownReportPassesThroughExtractReportText() {
        String md = "# 告警分析报告\n\n正文";
        assertEquals(md, RootCauseAnalysisNode.extractReportText(md).orElse(null));
        assertTrue(RootCauseAnalysisNode.extractReportText("{\"decision\":\"EXECUTE\",\"step\":\"查日志\"}").isEmpty());
    }

    @Test
    void riskScoreCapsAt100() {
        assertEquals(10, RiskGateNode.score("L0", 1.0, "medium"));
        assertEquals(50, RiskGateNode.score("L1", 1.0, "high"));
        assertEquals(100, RiskGateNode.score("L2", 0.0, "high"));
    }
}
