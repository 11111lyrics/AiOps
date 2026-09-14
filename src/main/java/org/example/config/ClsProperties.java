package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * CLS 云日志服务配置，并提供 Agent 提示词中的日志主题清单。
 */
@Configuration
@ConfigurationProperties(prefix = "cls")
public class ClsProperties {

    private static final Set<String> RAW_TOPIC_KEYS = Set.of("mysql-slow");

    private String region = "ap-chengdu";
    private String logsetId = "";
    private Map<String, String> topics = new LinkedHashMap<>();

    /**
     * 根据服务键解析 CLS 日志主题名称，如 user-service → tjxt-dev-user-service-log-ap-chengdu。
     * mysql-slow 不补 -service：tjxt-dev-mysql-slow-log-ap-chengdu。
     */
    public String resolveTopicName(String serviceKey) {
        if (serviceKey == null || serviceKey.isBlank()) {
            return "";
        }
        String normalized = (serviceKey.endsWith("-service") || RAW_TOPIC_KEYS.contains(serviceKey))
                ? serviceKey
                : serviceKey + "-service";
        return "tjxt-dev-" + normalized + "-log-" + region;
    }

    /**
     * 根据服务键获取 TopicId（配置中存在时）。
     */
    public Optional<String> findTopicId(String serviceKey) {
        if (serviceKey == null || serviceKey.isBlank() || topics == null) {
            return Optional.empty();
        }
        String direct = topics.get(serviceKey);
        if (direct != null && !direct.isBlank()) {
            return Optional.of(direct);
        }
        String withSuffix = serviceKey.endsWith("-service") ? serviceKey : serviceKey + "-service";
        String id = topics.get(withSuffix);
        return (id != null && !id.isBlank()) ? Optional.of(id) : Optional.empty();
    }

    /**
     * 生成注入 Agent 系统提示词的 CLS 主题清单块。
     */
    public String buildTopicsPromptBlock() {
        if (topics == null || topics.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## CLS 日志主题清单（tjxt-dev 环境）\n");
        sb.append("- **Region**: ").append(region).append("\n");
        if (logsetId != null && !logsetId.isBlank()) {
            sb.append("- **日志集 ID**: ").append(logsetId).append("\n");
        }
        sb.append("- **采集路径**: 微服务 `/data/tjxt/logs/{service}/**/spring.log`；MySQL 慢查询 `/data/tjxt/logs/mysql/**/slow.log`（LogListener 机器组 `tjxt`）\n");
        sb.append("- 告警 label 中的服务名通常对应下表「服务键」；查日志时优先用 TopicId，或用 GetTopicInfoByName 按主题名搜索\n");
        sb.append("- 定位慢 SQL 用服务键 **mysql-slow**（主题 `tjxt-dev-mysql-slow-log-ap-chengdu`），全文搜 `Query_time` / `Rows_examined` / `SELECT`\n");
        sb.append("- 本环境几乎无键值索引：禁止 `level:ERROR` 和 `UnknownHostException:` 这类字段检索，用全文 `ERROR` 或 `\"UnknownHostException\"`；SearchLog 前须 TextToSearchLogQuery\n\n");
        sb.append("| 服务键 | 日志主题名称 | TopicId |\n");
        sb.append("|--------|-------------|--------|\n");

        topics.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sb.append("| ")
                        .append(entry.getKey())
                        .append(" | ")
                        .append(resolveTopicName(entry.getKey()))
                        .append(" | ")
                        .append(entry.getValue())
                        .append(" |\n"));

        sb.append("\n");
        return sb.toString();
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getLogsetId() {
        return logsetId;
    }

    public void setLogsetId(String logsetId) {
        this.logsetId = logsetId;
    }

    public Map<String, String> getTopics() {
        return topics;
    }

    public void setTopics(Map<String, String> topics) {
        this.topics = topics != null ? topics : new LinkedHashMap<>();
    }
}
