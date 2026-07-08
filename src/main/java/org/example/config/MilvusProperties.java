package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "milvus")
public class MilvusProperties {

    private String host = "localhost";
    private Integer port = 19530;
    private String username = "";
    private String password = "";
    private String database = "default";
    private Long timeout = 10000L;
    /** 启动连接重试次数（Milvus Proxy 冷启动时可能尚未就绪） */
    private Integer connectRetryMaxAttempts = 12;
    /** 每次重试间隔（毫秒） */
    private Long connectRetryIntervalMs = 5000L;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public Long getTimeout() {
        return timeout;
    }

    public void setTimeout(Long timeout) {
        this.timeout = timeout;
    }

    public String getAddress() {
        return host + ":" + port;
    }
}
