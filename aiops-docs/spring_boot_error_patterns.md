# Spring Boot 常见日志错误模式（tjxt 微服务）

## 说明

tjxt 各微服务使用 Spring Boot，日志为单行文本写入 `spring.log`，CLS 以全文索引检索。以下为常见 ERROR 模式及处理方向。

## 数据库相关

| 日志关键词 | 可能原因 | 建议处理 |
|-----------|----------|----------|
| `Communications link failure` | MySQL 不可达 | 检查 192.168.150.101:3306、防火墙、连接池 |
| `Too many connections` | 连接池泄漏或 DB max_connections 过小 | 重启实例、调大连接数、查慢 SQL |
| `Deadlock found` | 事务死锁 | 优化 SQL、调整事务顺序 |
| `Table doesn't exist` | 迁移未执行 | 执行 schema 迁移 |

**优先查**: 告警 label 对应服务主题，CQL: `SQLException OR "Communications link failure"`

## Redis / 缓存

| 日志关键词 | 可能原因 |
|-----------|----------|
| `RedisConnectionFailureException` | Redis 宕机或网络不通 |
| `READONLY You can't write` | Redis 主从切换 |

CQL: `Redis OR "Connection reset" OR READONLY`

## 微服务调用（OpenFeign / RestTemplate）

| 日志关键词 | 可能原因 |
|-----------|----------|
| `feign.RetryableException` | 下游超时或不可用 |
| `503 Service Unavailable` | 下游未注册或过载 |
| `Read timed out` | 下游响应慢 |

处理：日志中找被调服务名，再查该服务 CLS 主题。

## JVM / 资源

| 日志关键词 | 告警关联 |
|-----------|----------|
| `OutOfMemoryError` | HighMemoryUsage |
| `GC overhead limit exceeded` | HighMemoryUsage |
| `java.lang.OutOfMemoryError: Java heap space` | 需扩容或修内存泄漏 |

## 业务异常

| 日志关键词 | 说明 |
|-----------|------|
| `BusinessException` | 业务校验失败，通常非基础设施问题 |
| `MethodArgumentNotValidException` | 参数校验失败 |
| `NullPointerException` | 代码缺陷，需 DescribeLogContext 看完整栈 |

## 查询技巧

1. 先用 `ERROR` 缩小范围
2. 再用异常类名精确搜索，如 `NullPointerException`
3. 用 DescribeLogContext 看同一 trace/request 前后日志（若有 traceId 可搜 traceId）
