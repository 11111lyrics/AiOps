package org.example.aiops;

import org.example.aiops.model.Blackboard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * 节点 → SSE 的进度总线。节点只知道 incidentId，不持有 SseEmitter；
 * Controller 在段 A / 段 B 期间注册消费者，结束后注销。
 *
 * 事件类型：
 * - content：人可读进度行或最终报告（评测脚本与旧前端只认这一类）
 * - stage：节点事件 JSON {incidentId,node,status,message}
 * - approval：待审批卡 JSON
 * - incident：终态摘要 JSON
 */
@Component
public class IncidentProgressBus {

    private static final Logger logger = LoggerFactory.getLogger(IncidentProgressBus.class);

    public static final String TYPE_CONTENT = "content";
    public static final String TYPE_STAGE = "stage";
    public static final String TYPE_APPROVAL = "approval";
    public static final String TYPE_INCIDENT = "incident";

    private final Map<String, BiConsumer<String, String>> sinks = new ConcurrentHashMap<>();

    public void register(String incidentId, BiConsumer<String, String> sink) {
        if (incidentId != null && sink != null) {
            sinks.put(incidentId, sink);
        }
    }

    public void unregister(String incidentId) {
        if (incidentId != null) {
            sinks.remove(incidentId);
        }
    }

    public void content(String incidentId, String text) {
        publish(incidentId, TYPE_CONTENT, text);
    }

    public void stage(String incidentId, String node, String status, String message) {
        Map<String, Object> payload = Map.of(
                "incidentId", incidentId == null ? "" : incidentId,
                "node", node == null ? "" : node,
                "status", status == null ? "" : status,
                "message", message == null ? "" : message);
        publish(incidentId, TYPE_STAGE, Blackboard.toJsonQuietly(payload));
    }

    public void json(String incidentId, String type, Object payload) {
        publish(incidentId, type, Blackboard.toJsonQuietly(payload));
    }

    private void publish(String incidentId, String type, String data) {
        if (incidentId == null) {
            return;
        }
        BiConsumer<String, String> sink = sinks.get(incidentId);
        if (sink == null) {
            return;
        }
        try {
            sink.accept(type, data);
        } catch (Exception e) {
            logger.debug("进度推送失败 incident={} type={}: {}", incidentId, type, e.getMessage());
        }
    }
}
