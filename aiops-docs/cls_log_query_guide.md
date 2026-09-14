# CLS 日志查询指南（tjxt-dev / ap-chengdu）

## 环境信息

| 项目 | 值 |
|------|-----|
| 地域 Region | `ap-chengdu` |
| 日志集 | `tjxt-dev-logset-ap-chengdu` |
| 日志集 ID | `bd870dc4-1b4d-4b60-b377-c7d1e297e9f4` |
| 采集方式 | LogListener 机器组 `tjxt`（192.168.150.101） |
| 采集路径 | 微服务 `/data/tjxt/logs/{service}/**/spring.log`；慢查询 `/data/tjxt/logs/mysql/**/slow.log` |

## 日志主题命名规则

```
tjxt-dev-{service-key}-log-ap-chengdu
```

示例：`user-service` → `tjxt-dev-user-service-log-ap-chengdu`  
MySQL 慢查询：`mysql-slow` → `tjxt-dev-mysql-slow-log-ap-chengdu`

## MCP 工具调用顺序（必须遵守）

1. **ConvertTimeStringToTimestamp**：计算 SearchLog 的 From/To（毫秒），默认查近 15 分钟
2. **GetTopicInfoByName**（可选）：`searchText=tjxt-dev-user-service-log-ap-chengdu`，`Region=ap-chengdu`
3. **TextToSearchLogQuery**：将自然语言转为 CQL（SearchLog 前必须调用）
4. **SearchLog**：执行检索
5. **DescribeLogContext**：查看某条 ERROR 的前后上下文（需 PkgId、PkgLogId、Time）

查日志使用上述 CLS MCP 工具，禁止编造日志内容。  
禁止对日志主题调用 `QueryMetric`（会报 `the topic is not metric topic`）。指标用 `queryPrometheusAlerts`。

## 常用 CQL 示例

| 场景 | 建议 CQL（经 TextToSearchLogQuery 生成） |
|------|----------------------------------------|
| 查 ERROR | `ERROR` 或 `level:ERROR` |
| 查异常栈 | `Exception OR StackTrace OR "at org."` |
| 查 5xx | `500 OR "Internal Server Error" OR status:500` |
| 查数据库 | `SQLException OR "Connection refused" OR "Too many connections"` |
| 查超时 | `timeout OR TimeoutException OR "Read timed out"` |
| 查网关路由 | `gateway OR route OR "503 Service Unavailable"` |
| 查慢 SQL | 主题 `tjxt-dev-mysql-slow-log-ap-chengdu`：`Query_time OR Rows_examined OR SELECT` |

Spring Boot 单行日志为全文检索，优先用关键词 `ERROR`、`Exception`、异常类名。

## 服务与日志主题对照

| 服务键 | 日志主题名称 |
|--------|-------------|
| gateway-service | tjxt-dev-gateway-service-log-ap-chengdu |
| auth-service | tjxt-dev-auth-service-log-ap-chengdu |
| user-service | tjxt-dev-user-service-log-ap-chengdu |
| course-service | tjxt-dev-course-service-log-ap-chengdu |
| trade-service | tjxt-dev-trade-service-log-ap-chengdu |
| pay-service | tjxt-dev-pay-service-log-ap-chengdu |
| 其他服务 | tjxt-dev-{service}-log-ap-chengdu |
| mysql-slow | tjxt-dev-mysql-slow-log-ap-chengdu |

## 告警 → 日志主题映射建议

1. 从 **queryPrometheusAlerts** 获取告警 label 中的 `service` / `job` / `instance`
2. 入口流量问题优先查 **gateway-service**
3. 登录/token 问题查 **auth-service**
4. 业务接口错误查 label 对应微服务主题
5. 跨服务调用失败：先查调用方，再查被调用方
6. 慢查询 / 缺索引：查 **mysql-slow**（`Query_time`、`Rows_examined`、SQL 文本）

## 空结果处理

- 后端未产生日志或尚未报错：SearchLog 返回 0 条属正常
- 确认 LogListener 在线、采集规则 Output 指向正确主题
- 扩大时间范围至 1 小时重试
