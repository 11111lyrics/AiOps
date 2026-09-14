package org.example.aiops;

import org.example.agent.tool.QueryMetricsTools.SimplifiedAlert;
import org.example.aiops.config.AiOpsOrchestrationProperties;
import org.example.aiops.config.AiOpsOrchestrationProperties.ComponentDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopologyResolverTest {

    private TopologyResolver resolver;

    @BeforeEach
    void setUp() {
        AiOpsOrchestrationProperties properties = new AiOpsOrchestrationProperties();
        properties.getTopology().getAlertComponents().put("MySQLDown", "mysql");
        properties.getTopology().getAlertComponents().put("MySQLSlowQueries", "mysql");
        properties.getTopology().getAlertComponents().put("ServiceUnavailable", "course");
        properties.getTopology().getComponents().put("mysql", def("high"));
        properties.getTopology().getComponents().put("redis", def("high"));
        ComponentDef course = def("medium");
        course.setDependsOn(List.of("mysql", "redis"));
        course.setAliases(List.of("course-service", "tj-course"));
        properties.getTopology().getComponents().put("course", course);
        ComponentDef gateway = def("high");
        gateway.setDependsOn(List.of("course"));
        properties.getTopology().getComponents().put("gateway", gateway);
        resolver = new TopologyResolver(properties);
    }

    @Test
    void mapsAlertNameToComponent() {
        SimplifiedAlert alert = new SimplifiedAlert();
        alert.setAlertName("MySQLDown");
        assertEquals("mysql", resolver.resolveComponent(alert));
        SimplifiedAlert slow = new SimplifiedAlert();
        slow.setAlertName("MySQLSlowQueries");
        assertEquals("mysql", resolver.resolveComponent(slow));
    }

    @Test
    void serviceUnavailableUsesLabelWhenPresent() {
        SimplifiedAlert alert = new SimplifiedAlert();
        alert.setAlertName("ServiceUnavailable");
        alert.setService("course-service");
        assertEquals("course", resolver.resolveComponent(alert));
    }

    @Test
    void rootCandidatesDropDependents() {
        Set<String> alerted = new LinkedHashSet<>(List.of("mysql", "course", "gateway"));
        List<String> roots = resolver.rootCandidates(alerted);
        assertEquals(List.of("mysql"), roots);
    }

    @Test
    void independentAlertsAreAllRoots() {
        Set<String> alerted = new LinkedHashSet<>(List.of("mysql", "redis"));
        List<String> roots = resolver.rootCandidates(alerted);
        assertTrue(roots.containsAll(List.of("mysql", "redis")));
        assertEquals(2, roots.size());
    }

    private static ComponentDef def(String criticality) {
        ComponentDef d = new ComponentDef();
        d.setCriticality(criticality);
        return d;
    }
}
