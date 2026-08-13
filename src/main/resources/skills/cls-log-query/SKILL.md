---
name: cls-log-query
description: 查询腾讯云 CLS 日志时使用。包含 MCP 工具调用顺序、Region、主题命名和 CQL。查日志前必须先加载。
---

# CLS 日志查询

适用于 tjxt-dev / 腾讯云 CLS。查日志前必须按下面顺序调用 MCP 工具，禁止凭记忆跳步。

## 环境

- **Region**：必须使用连字符格式 `ap-chengdu`，禁止 `apchengdu` 或下划线
- **日志集**：`tjxt-dev-logset-ap-chengdu`
- **采集路径**：`/data/tjxt/logs/{service}/**/spring.log`（LogListener 机器组 `tjxt`）

## MCP 调用顺序（必须遵守）

1. **ConvertTimeStringToTimestamp**（或已有毫秒时间戳）：计算 `SearchLog` 的 From/To，默认近 15 分钟
2. **GetTopicInfoByName**（可选，未知 TopicId 时）：按主题名查找，`Region=ap-chengdu`
3. **TextToSearchLogQuery**：把自然语言转为 CQL。**调用 SearchLog 前必须先走这一步**
4. **SearchLog**：执行检索（From/To 为毫秒时间戳）
5. **DescribeLogContext**：查看某条 ERROR 的前后上下文（需 PkgId、PkgLogId、Time）

告警类：`DescribeAlarms` / `DescribeAlertRecordHistory` / `GetAlarmLog`  
时间反查：`ConvertTimestampToTimeString`

禁止使用已下线的本地工具 `queryLogs` / `getAvailableLogTopics`。禁止编造日志内容。同一工具连续失败 3 次或返回空，停止该方向并如实说明。

## 主题命名

```
tjxt-dev-{service-key}-log-ap-chengdu
```

示例：`user-service` → `tjxt-dev-user-service-log-ap-chengdu`  
查日志优先用 TopicId；没有 Id 时用 `GetTopicInfoByName` 按主题名搜索。

当前环境的服务键 / 主题名 / TopicId 对照表由加载本 skill 时附加（来自运行时配置）。

## 告警 → 主题

1. 用 `queryPrometheusAlerts` 取告警 label 的 `service` / `job` / `instance`
2. 入口流量优先查 **gateway-service**
3. 登录 / token 查 **auth-service**
4. 业务接口错误查 label 对应微服务主题
5. 跨服务失败：先查调用方，再查被调用方

## 常用 CQL（经 TextToSearchLogQuery 生成）

| 场景 | 关键词 |
|------|--------|
| ERROR | `ERROR` 或 `level:ERROR` |
| 异常栈 | `Exception OR StackTrace OR "at org."` |
| 5xx | `500 OR "Internal Server Error" OR status:500` |
| 数据库 | `SQLException OR "Connection refused" OR "Too many connections"` |
| 超时 | `timeout OR TimeoutException OR "Read timed out"` |
| 网关 | `gateway OR route OR "503 Service Unavailable"` |

Spring Boot 单行日志按全文检索，优先用 `ERROR`、`Exception`、异常类名。

## 空结果

- 近窗口内没有报错时，SearchLog 返回 0 条是正常现象，不要编造日志
- 可扩大到近 1 小时再查一次
- 仍为空则如实反馈，不要假装查到了内容
