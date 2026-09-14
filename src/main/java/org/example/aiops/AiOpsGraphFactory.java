package org.example.aiops;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import org.example.aiops.model.Blackboard;
import org.example.aiops.node.ExperienceDistillNode;
import org.example.aiops.node.IncidentIntakeNode;
import org.example.aiops.node.PlaybookDecideNode;
import org.example.aiops.node.PlaybookExecuteNode;
import org.example.aiops.node.RemediationPlanNode;
import org.example.aiops.node.RiskGateNode;
import org.example.aiops.node.RootCauseAnalysisNode;
import org.example.aiops.node.VerificationNode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 段 A / 段 B / 段 Revise 三张 CompiledGraph。节点 Bean 共用，条件边按 risk.decision 分流。
 * L2 在段 A 结束落库；同意走段 B；其他建议走段 Revise（从决策节点重选）。
 */
@Configuration
public class AiOpsGraphFactory {

    public static final String ROUTE_EXECUTE = "execute";
    public static final String ROUTE_DISTILL = "distill";
    public static final String ROUTE_END = "end";

    @Bean("aiOpsPhaseA")
    public CompiledGraph phaseA(IncidentIntakeNode intake,
                                RootCauseAnalysisNode rca,
                                PlaybookDecideNode decide,
                                RemediationPlanNode plan,
                                RiskGateNode risk,
                                PlaybookExecuteNode execute,
                                VerificationNode verify,
                                ExperienceDistillNode distill) throws GraphStateException {
        StateGraph graph = new StateGraph("aiops_phase_a", Blackboard.keyStrategies())
                .addNode(IncidentIntakeNode.NAME, node_async(intake))
                .addNode(RootCauseAnalysisNode.NAME, node_async(rca))
                .addNode(PlaybookDecideNode.NAME, node_async(decide))
                .addNode(RemediationPlanNode.NAME, node_async(plan))
                .addNode(RiskGateNode.NAME, node_async(risk))
                .addNode(PlaybookExecuteNode.NAME, node_async(execute))
                .addNode(VerificationNode.NAME, node_async(verify))
                .addNode(ExperienceDistillNode.NAME, node_async(distill));

        graph.addEdge(START, IncidentIntakeNode.NAME);
        graph.addEdge(IncidentIntakeNode.NAME, RootCauseAnalysisNode.NAME);
        graph.addEdge(RootCauseAnalysisNode.NAME, PlaybookDecideNode.NAME);
        graph.addEdge(PlaybookDecideNode.NAME, RemediationPlanNode.NAME);
        graph.addEdge(RemediationPlanNode.NAME, RiskGateNode.NAME);
        graph.addConditionalEdges(RiskGateNode.NAME, edge_async(AiOpsGraphFactory::routeAfterRisk), Map.of(
                ROUTE_EXECUTE, PlaybookExecuteNode.NAME,
                ROUTE_DISTILL, ExperienceDistillNode.NAME,
                ROUTE_END, END));
        graph.addEdge(PlaybookExecuteNode.NAME, VerificationNode.NAME);
        graph.addEdge(VerificationNode.NAME, ExperienceDistillNode.NAME);
        graph.addEdge(ExperienceDistillNode.NAME, END);

        return graph.compile(compileConfig(20));
    }

    @Bean("aiOpsPhaseRevise")
    public CompiledGraph phaseRevise(PlaybookDecideNode decide,
                                     RemediationPlanNode plan,
                                     RiskGateNode risk,
                                     PlaybookExecuteNode execute,
                                     VerificationNode verify,
                                     ExperienceDistillNode distill) throws GraphStateException {
        StateGraph graph = new StateGraph("aiops_phase_revise", Blackboard.keyStrategies())
                .addNode(PlaybookDecideNode.NAME, node_async(decide))
                .addNode(RemediationPlanNode.NAME, node_async(plan))
                .addNode(RiskGateNode.NAME, node_async(risk))
                .addNode(PlaybookExecuteNode.NAME, node_async(execute))
                .addNode(VerificationNode.NAME, node_async(verify))
                .addNode(ExperienceDistillNode.NAME, node_async(distill));

        graph.addEdge(START, PlaybookDecideNode.NAME);
        graph.addEdge(PlaybookDecideNode.NAME, RemediationPlanNode.NAME);
        graph.addEdge(RemediationPlanNode.NAME, RiskGateNode.NAME);
        graph.addConditionalEdges(RiskGateNode.NAME, edge_async(AiOpsGraphFactory::routeAfterRisk), Map.of(
                ROUTE_EXECUTE, PlaybookExecuteNode.NAME,
                ROUTE_DISTILL, ExperienceDistillNode.NAME,
                ROUTE_END, END));
        graph.addEdge(PlaybookExecuteNode.NAME, VerificationNode.NAME);
        graph.addEdge(VerificationNode.NAME, ExperienceDistillNode.NAME);
        graph.addEdge(ExperienceDistillNode.NAME, END);

        return graph.compile(compileConfig(20));
    }

    @Bean("aiOpsPhaseB")
    public CompiledGraph phaseB(PlaybookExecuteNode execute,
                                VerificationNode verify,
                                ExperienceDistillNode distill) throws GraphStateException {
        StateGraph graph = new StateGraph("aiops_phase_b", Blackboard.keyStrategies())
                .addNode(PlaybookExecuteNode.NAME, node_async(execute))
                .addNode(VerificationNode.NAME, node_async(verify))
                .addNode(ExperienceDistillNode.NAME, node_async(distill));

        graph.addEdge(START, PlaybookExecuteNode.NAME);
        graph.addEdge(PlaybookExecuteNode.NAME, VerificationNode.NAME);
        graph.addEdge(VerificationNode.NAME, ExperienceDistillNode.NAME);
        graph.addEdge(ExperienceDistillNode.NAME, END);

        return graph.compile(compileConfig(10));
    }

    private static CompileConfig compileConfig(int recursionLimit) {
        return CompileConfig.builder().recursionLimit(recursionLimit).build();
    }

    static String routeAfterRisk(OverAllState state) {
        String decision = Blackboard.riskDecisionCode(state);
        if ("AUTO_EXECUTE".equals(decision)) {
            return ROUTE_EXECUTE;
        }
        if ("PENDING_APPROVAL".equals(decision)) {
            return ROUTE_END;
        }
        return ROUTE_DISTILL;
    }
}
