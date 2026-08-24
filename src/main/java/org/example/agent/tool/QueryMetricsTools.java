package org.example.agent.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Prometheus 告警查询工具
 * 用于查询 Prometheus 的活动告警信息
 */
@Component
public class QueryMetricsTools {

    private static final Logger logger = LoggerFactory.getLogger(QueryMetricsTools.class);

    /** 工具名常量，用于动态构建提示词 */
    public static final String TOOL_QUERY_PROMETHEUS_ALERTS = "queryPrometheusAlerts";
    private static final int FETCH_RETRIES = 8;
    private static final String USER_AGENT = "SuperBizAgent";
    private static final String FALLBACK_RECALL_QUERY = "当前活动告警 宕机 不可用 连接失败 根因 排查";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${prometheus.base-url}")
    private String prometheusBaseUrl;

    @Value("${prometheus.timeout:10}")
    private int timeout;

    @jakarta.annotation.PostConstruct
    public void init() {
        logger.info("✅ QueryMetricsTools 初始化成功, Prometheus URL: {}", prometheusBaseUrl);
    }
    
    /**
     * 查询 Prometheus 活动告警
     * 该工具从 Prometheus 告警系统检索所有当前活动/触发的告警，包括标签、注释、状态和值
     */
    @Tool(description = "Query active alerts from Prometheus alerting system. " +
            "This tool retrieves all currently active/firing alerts including their labels, annotations, state, and values. " +
            "Use this tool when you need to check what alerts are currently firing, investigate alert conditions, or monitor alert status.")
    public String queryPrometheusAlerts() {
        logger.info("开始查询 Prometheus 活动告警");
        
        try {
            PrometheusAlertsResult result = fetchPrometheusAlerts();
            
            if (!"success".equals(result.getStatus())) {
                return buildErrorResponse("Prometheus API 返回非成功状态: " + result.getStatus(), result.getError());
            }
            List<PrometheusAlert> rawAlerts = result.getData() != null && result.getData().getAlerts() != null
                    ? result.getData().getAlerts() : List.of();

            Set<String> seen = new HashSet<>();
            List<SimplifiedAlert> simplifiedAlerts = new ArrayList<>();

            for (PrometheusAlert alert : rawAlerts) {
                Map<String, String> labels = alert.getLabels() != null ? alert.getLabels() : Map.of();
                Map<String, String> annotations = alert.getAnnotations() != null ? alert.getAnnotations() : Map.of();
                String state = alert.getState() == null ? "" : alert.getState();
                if (!"firing".equalsIgnoreCase(state) && !"pending".equalsIgnoreCase(state)) {
                    continue;
                }
                String alertName = labels.getOrDefault("alertname", "");
                String instance = firstLabel(labels, "instance", "pod", "container");
                String key = alertName + "|" + instance;
                if (!seen.add(key)) {
                    continue;
                }

                SimplifiedAlert simplified = new SimplifiedAlert();
                simplified.setAlertName(alertName);
                simplified.setInstance(instance);
                simplified.setSeverity(firstLabel(labels, "severity", "level"));
                simplified.setService(firstLabel(labels, "service", "job", "exported_job"));
                simplified.setDescription(annotations.getOrDefault("description",
                        annotations.getOrDefault("summary", "")));
                simplified.setState(state);
                simplified.setActiveAt(alert.getActiveAt());
                simplified.setDuration(calculateDuration(alert.getActiveAt()));
                simplifiedAlerts.add(simplified);
                if (simplifiedAlerts.size() >= 30) {
                    break;
                }
            }
            
            PrometheusAlertsOutput output = new PrometheusAlertsOutput();
            output.setSuccess(true);
            output.setAlerts(simplifiedAlerts);
            output.setMessage(String.format("成功检索到 %d 个活动告警", simplifiedAlerts.size()));
            
            String jsonResult = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
            logger.info("Prometheus 告警查询完成: 找到 {} 个告警", simplifiedAlerts.size());
            
            return jsonResult;
            
        } catch (Exception e) {
            logger.error("查询 Prometheus 告警失败", e);
            return buildErrorResponse("查询失败", e.getMessage());
        }
    }
    
    /**
     * 从当前告警 JSON 拼经验召回查询：用 firing 告警名/实例/描述，而不是写死 CPU/内存。
     */
    public String toExperienceRecallQuery(String alertsJson) {
        List<SimplifiedAlert> firing = extractFiringAlerts(alertsJson);
        if (firing.isEmpty()) {
            return FALLBACK_RECALL_QUERY;
        }
        StringBuilder sb = new StringBuilder();
        for (SimplifiedAlert alert : firing) {
            if (alert.getAlertName() != null && !alert.getAlertName().isBlank()) {
                sb.append(alert.getAlertName()).append(' ');
            }
            if (alert.getInstance() != null && !alert.getInstance().isBlank()) {
                sb.append(alert.getInstance()).append(' ');
            }
            if (alert.getService() != null && !alert.getService().isBlank()) {
                sb.append(alert.getService()).append(' ');
            }
            if (alert.getDescription() != null && !alert.getDescription().isBlank()) {
                sb.append(alert.getDescription()).append(' ');
            }
        }
        sb.append("告警 根因 排查");
        return sb.toString().trim();
    }

    public List<String> extractFiringNames(String alertsJson) {
        return extractFiringAlerts(alertsJson).stream()
                .map(SimplifiedAlert::getAlertName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .collect(Collectors.toList());
    }

    public List<SimplifiedAlert> extractFiringAlerts(String alertsJson) {
        if (alertsJson == null || alertsJson.isBlank()) {
            return List.of();
        }
        try {
            PrometheusAlertsOutput output = objectMapper.readValue(alertsJson, PrometheusAlertsOutput.class);
            if (output == null || !output.isSuccess() || output.getAlerts() == null) {
                return List.of();
            }
            return output.getAlerts().stream()
                    .filter(a -> a != null && "firing".equalsIgnoreCase(a.getState()))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.debug("解析告警 JSON 失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 从 Prometheus API 获取告警数据。
     * Windows 到 tjxt NAT 常见 Connection reset：不用 OkHttp（gzip / 连接池），
     * 改走与评测脚本相近的 HttpURLConnection，并做指数退避。不回落到经验库虚机。
     */
    private PrometheusAlertsResult fetchPrometheusAlerts() throws Exception {
        String apiUrl = prometheusBaseUrl + "/api/v1/alerts";
        Exception last = null;
        for (int i = 1; i <= FETCH_RETRIES; i++) {
            logger.debug("请求 Prometheus API ({}/{}): {}", i, FETCH_RETRIES, apiUrl);
            try {
                return getAlertsOnce(apiUrl);
            } catch (Exception e) {
                last = e;
                logger.warn("查询 Prometheus 告警失败 ({}/{}): {}", i, FETCH_RETRIES, e.getMessage());
                if (i < FETCH_RETRIES) {
                    try {
                        Thread.sleep(Math.min(8_000L, 500L * (1L << Math.min(i - 1, 4))));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw ie;
                    }
                }
            }
        }
        throw last != null ? last : new RuntimeException("查询 Prometheus 告警失败");
    }

    private PrometheusAlertsResult getAlertsOnce(String apiUrl) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(apiUrl).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(timeout * 1000);
        conn.setReadTimeout(timeout * 1000);
        conn.setUseCaches(false);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Accept-Encoding", "identity");
        conn.setRequestProperty("Connection", "close");
        try {
            int code = conn.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String body = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            if (code < 200 || code >= 300) {
                throw new RuntimeException("HTTP 请求失败: " + code);
            }
            if (body.isBlank()) {
                throw new RuntimeException("Prometheus 返回空响应体");
            }
            return objectMapper.readValue(body, PrometheusAlertsResult.class);
        } finally {
            conn.disconnect();
        }
    }

    private static String firstLabel(Map<String, String> labels, String... keys) {
        for (String key : keys) {
            String value = labels.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
    
    /**
     * 计算从 activeAt 到现在的持续时间
     */
    private String calculateDuration(String activeAtStr) {
        try {
            Instant activeAt = Instant.parse(activeAtStr);
            Duration duration = Duration.between(activeAt, Instant.now());
            
            long hours = duration.toHours();
            long minutes = duration.toMinutes() % 60;
            long seconds = duration.getSeconds() % 60;
            
            if (hours > 0) {
                return String.format("%dh%dm%ds", hours, minutes, seconds);
            } else if (minutes > 0) {
                return String.format("%dm%ds", minutes, seconds);
            } else {
                return String.format("%ds", seconds);
            }
        } catch (Exception e) {
            logger.warn("解析时间失败: {}", activeAtStr, e);
            return "unknown";
        }
    }
    
    /**
     * 构建错误响应
     */
    private String buildErrorResponse(String message, String error) {
        try {
            PrometheusAlertsOutput output = new PrometheusAlertsOutput();
            output.setSuccess(false);
            output.setMessage(message);
            output.setError(error);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        } catch (Exception e) {
            return String.format("{\"success\":false,\"message\":\"%s\",\"error\":\"%s\"}", message, error);
        }
    }
    
    // ==================== 数据模型 ====================
    
    /**
     * Prometheus 告警信息结构
     */
    @Data
    public static class PrometheusAlert {
        private Map<String, String> labels;
        private Map<String, String> annotations;
        private String state;
        private String activeAt;
        private String value;
    }
    
    /**
     * Prometheus 告警查询结果
     */
    @Data
    public static class PrometheusAlertsResult {
        private String status;
        private AlertsData data;
        private String error;
        private String errorType;
    }
    
    @Data
    public static class AlertsData {
        private List<PrometheusAlert> alerts = new ArrayList<>();
    }
    
    /**
     * 简化的告警信息
     */
    @Data
    public static class SimplifiedAlert {
        @JsonProperty("alert_name")
        private String alertName;

        @JsonProperty("instance")
        private String instance;

        @JsonProperty("severity")
        private String severity;

        @JsonProperty("service")
        private String service;
        
        @JsonProperty("description")
        private String description;
        
        @JsonProperty("state")
        private String state;
        
        @JsonProperty("active_at")
        private String activeAt;
        
        @JsonProperty("duration")
        private String duration;
    }
    
    /**
     * 告警查询输出
     */
    @Data
    public static class PrometheusAlertsOutput {
        @JsonProperty("success")
        private boolean success;
        
        @JsonProperty("alerts")
        private List<SimplifiedAlert> alerts;
        
        @JsonProperty("message")
        private String message;
        
        @JsonProperty("error")
        private String error;
    }
}
