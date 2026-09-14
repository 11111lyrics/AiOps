package org.example.aiops.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import org.example.aiops.IncidentProgressBus;
import org.example.aiops.IncidentRepository;
import org.example.aiops.model.Blackboard;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点公共骨架：进度推送、审计落库、白板输出组装。
 * 子类只实现 {@link #run(OverAllState, Map)}，把要写的 key 放进 out。
 */
public abstract class AbstractOpsNode implements NodeAction {

    protected final IncidentProgressBus bus;
    protected final IncidentRepository repository;

    protected AbstractOpsNode(IncidentProgressBus bus, IncidentRepository repository) {
        this.bus = bus;
        this.repository = repository;
    }

    /** 节点名（与图中注册名一致） */
    public abstract String name();

    protected abstract void run(OverAllState state, Map<String, Object> out) throws Exception;

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String incidentId = Blackboard.incidentId(state);
        Map<String, Object> out = new HashMap<>();
        bus.stage(incidentId, name(), "running", "");
        try {
            run(state, out);
            return out;
        } catch (Exception e) {
            repository.audit(incidentId, name(), "error", e.toString());
            bus.stage(incidentId, name(), "failed", e.getMessage());
            throw e;
        }
    }

    /** 人可读进度行：同时进 SSE content、audit_trail 与审计表 */
    protected void progress(OverAllState state, Map<String, Object> out, String text) {
        String incidentId = Blackboard.incidentId(state);
        bus.content(incidentId, text.endsWith("\n") ? text : text + "\n");
        appendTrail(out, text.strip());
        repository.audit(incidentId, name(), "progress", text.strip());
    }

    protected void done(OverAllState state, String message) {
        bus.stage(Blackboard.incidentId(state), name(), "done", message);
    }

    protected void audit(OverAllState state, String event, String detail) {
        repository.audit(Blackboard.incidentId(state), name(), event, detail);
    }

    /** AppendStrategy：每次只写入一条字符串，由图合并进 audit_trail 列表 */
    protected void appendTrail(Map<String, Object> out, String entry) {
        out.put(Blackboard.AUDIT_TRAIL, entry);
    }

    protected static String joinOrDash(List<String> items) {
        return items == null || items.isEmpty() ? "-" : String.join(", ", items);
    }
}
