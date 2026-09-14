package org.example.controller;

import lombok.Getter;
import lombok.Setter;
import org.example.aiops.AiOpsOrchestrationService;
import org.example.aiops.IncidentRepository.IncidentRow;
import org.example.controller.ChatController.ApiResponse;
import org.example.controller.ChatController.SseMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 一键排障事件查询与两段式审批。
 */
@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private static final Logger logger = LoggerFactory.getLogger(IncidentController.class);

    private final AiOpsOrchestrationService orchestrationService;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public IncidentController(AiOpsOrchestrationService orchestrationService) {
        this.orchestrationService = orchestrationService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Object>> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return ResponseEntity.ok(ApiResponse.success(orchestrationService.list(status, limit)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Object>> get(@PathVariable String id) {
        return orchestrationService.get(id)
                .map(row -> {
                    Map<String, Object> body = toMap(row);
                    body.put("audits", orchestrationService.audits(id));
                    return ResponseEntity.ok(ApiResponse.success((Object) body));
                })
                .orElseGet(() -> ResponseEntity.ok(ApiResponse.error("事件不存在")));
    }

    @PostMapping(value = "/{id}/approve", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter approve(@PathVariable String id, @RequestBody(required = false) ApprovalRequest request) {
        SseEmitter emitter = new SseEmitter(600000L);
        String approver = request == null ? null : request.getApprover();
        String comment = request == null ? null : request.getComment();
        executor.execute(() -> {
            try {
                orchestrationService.approve(id, approver, comment, sseSink(emitter));
                send(emitter, SseMessage.done(id));
                emitter.complete();
            } catch (IllegalArgumentException | IllegalStateException e) {
                sendError(emitter, e.getMessage());
            } catch (Exception e) {
                logger.error("审批执行失败 incident={}", id, e);
                sendError(emitter, "执行失败: " + e.getMessage());
            }
        });
        return emitter;
    }

    @PostMapping(value = "/{id}/revise", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter revise(@PathVariable String id, @RequestBody(required = false) ApprovalRequest request) {
        SseEmitter emitter = new SseEmitter(600000L);
        String approver = request == null ? null : request.getApprover();
        String suggestion = request == null ? null : firstNonBlank(request.getSuggestion(), request.getComment());
        executor.execute(() -> {
            try {
                orchestrationService.revise(id, approver, suggestion, sseSink(emitter));
                send(emitter, SseMessage.done(id));
                emitter.complete();
            } catch (IllegalArgumentException | IllegalStateException e) {
                sendError(emitter, e.getMessage());
            } catch (Exception e) {
                logger.error("按建议重选失败 incident={}", id, e);
                sendError(emitter, "重选失败: " + e.getMessage());
            }
        });
        return emitter;
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<ApiResponse<String>> reject(@PathVariable String id,
                                                      @RequestBody(required = false) ApprovalRequest request) {
        try {
            orchestrationService.reject(id, request == null ? null : request.getApprover(),
                    request == null ? null : request.getComment());
            return ResponseEntity.ok(ApiResponse.success("已拒绝"));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            logger.error("拒绝审批失败 incident={}", id, e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    static java.util.function.BiConsumer<String, String> sseSink(SseEmitter emitter) {
        return (type, data) -> {
            synchronized (emitter) {
                try {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.of(type, data), MediaType.APPLICATION_JSON));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    private static void send(SseEmitter emitter, SseMessage message) {
        synchronized (emitter) {
            try {
                emitter.send(SseEmitter.event().name("message").data(message, MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                logger.warn("SSE 发送失败: {}", e.getMessage());
            }
        }
    }

    private static void sendError(SseEmitter emitter, String message) {
        send(emitter, SseMessage.error(message));
        emitter.complete();
    }

    private static Map<String, Object> toMap(IncidentRow row) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", row.getId());
        body.put("status", row.getStatus());
        body.put("provider", row.getProvider());
        body.put("firingNames", row.getFiringNames());
        body.put("rootComponents", row.getRootComponents());
        body.put("riskLevel", row.getRiskLevel());
        body.put("decision", row.getDecision());
        body.put("reportMd", row.getReportMd());
        body.put("error", row.getError());
        body.put("approvedBy", row.getApprovedBy());
        body.put("approveComment", row.getApproveComment());
        body.put("createdAt", row.getCreatedAt());
        body.put("updatedAt", row.getUpdatedAt());
        return body;
    }

    @Getter
    @Setter
    public static class ApprovalRequest {
        private String approver;
        private String comment;
        /** 「其他建议」正文；revise 接口优先读这个字段 */
        private String suggestion;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }
}
