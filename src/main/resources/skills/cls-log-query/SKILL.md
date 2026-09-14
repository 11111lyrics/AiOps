---
name: cls-log-query
description: 查询腾讯云 CLS 日志时使用。包含 MCP 工具调用顺序、Region、主题命名和 CQL。查日志前必须先加载。
---

# CLS 日志查询

适用于 tjxt-dev / 腾讯云 CLS。查日志前必须按下面顺序调用 MCP 工具，禁止凭记忆跳步。

## 环境

- **Region**：必须使用连字符格式 `ap-chengdu`，禁止 `apchengdu` 或下划线
- **日志集**：`tjxt-dev-logset-ap-chengdu`
- **采集路径**：微服务 `/data/tjxt/logs/{service}/**/spring.log`；MySQL 慢查询 `/data/tjxt/logs/mysql/**/slow.log`（LogListener 机器组 `tjxt`）
- **索引**：业务主题只有全文索引 + `__CONTENT__`，**几乎没有键值字段**。CQL 里 `标识符:` 会被当成字段检索。禁止 `level:ERROR`，也禁止把异常类当字段：`UnknownHostException:` / `ConnectException:` / `SQLException:`（会报 `field: xxx is not indexed`）。MCP 工具说明里的 `level:ERROR` 示例对本环境无效，不要照抄。

## MCP 调用顺序（必须遵守）

1. **ConvertTimeStringToTimestamp**（或已有毫秒时间戳）：计算 `SearchLog` 的 From/To，默认近 15 分钟
2. **GetTopicInfoByName**（可选，未知 TopicId 时）：按主题名查找，`Region=ap-chengdu`
3. **TextToSearchLogQuery**：把自然语言转为 CQL。**调用 SearchLog 前必须先走这一步**，不要手写 `level:` 或 `异常类名:` 键值检索；异常类用全文 `"UnknownHostException"`
4. **SearchLog**：使用上一步生成的 CQL（From/To 为毫秒时间戳）。`Limit` 默认 10，不要超过 20
5. **DescribeLogContext**：查看某条代表性 ERROR 的前后上下文（需 PkgId、PkgLogId、Time）

告警类：`DescribeAlarms` / `DescribeAlertRecordHistory` / `GetAlarmLog`  
时间反查：`ConvertTimestampToTimeString`

**禁止 `QueryMetric` / `QueryMetrics`**：本环境全部是日志主题（log topic），不是指标主题（metric topic）。对 `tjxt-dev-*-log-ap-chengdu` 调 `QueryMetric` 会报 `the topic is not metric topic`。指标只用 `queryPrometheusAlerts`，慢 SQL 用 `SearchLog` 查 `mysql-slow`。

禁止编造日志内容。查日志使用 CLS MCP 工具，不要凭记忆填写日志原文。

## 失败后禁止原样重试

- `SearchLog` 若报 `not indexed` / `SyntaxError` / `field: xxx`：把报错里的字段名改成**带引号的全文词**（`field: UnknownHostException` → `"UnknownHostException"`），或重新调用 `TextToSearchLogQuery`；**禁止用同一条带冒号的 Query 再调 SearchLog**
- 同一工具连续失败 3 次或返回空，停止该方向并如实说明

## 主题命名

```
tjxt-dev-{service-key}-log-ap-chengdu
```

示例：`user-service` → `tjxt-dev-user-service-log-ap-chengdu`  
MySQL 慢查询：`mysql-slow` → `tjxt-dev-mysql-slow-log-ap-chengdu`（不要补 `-service`）  
查日志优先用 TopicId；没有 Id 时用 `GetTopicInfoByName` 按主题名搜索。

当前环境的服务键 / 主题名 / TopicId 对照表由加载本 skill 时附加（来自运行时配置）。

## 告警 → 主题

1. 用 `queryPrometheusAlerts` 取告警 label 的 `service` / `job` / `instance`
2. 入口流量优先查 **gateway-service**
3. 登录 / token 查 **auth-service**
4. 业务接口错误查 label 对应微服务主题
5. 跨服务失败：先查调用方，再查被调用方
6. 慢 SQL / 缺索引：查 **mysql-slow**，不要只查微服务 `spring.log`

## 常用 CQL（必须经 TextToSearchLogQuery 生成后再用）

本环境是 Spring Boot 单行 `spring.log`，只用**全文检索**。异常类名不要加冒号。

| 场景 | 关键词（全文，禁止写成 field:） |
|------|----------------|
| ERROR | `ERROR` |
| 异常栈 | `"Exception" OR StackTrace` |
| 未知主机 | `"UnknownHostException"` |
| 5xx | `500 OR "Internal Server Error"` |
| 数据库 | `"SQLException" OR "Communications link failure" OR "Too many connections"` |
| 超时 | `timeout OR "TimeoutException"` |
| 网关 | `"服务不存在" OR "503"` |
| MySQL 慢查询 | `"Query_time" OR "Rows_examined" OR SELECT`（主题 mysql-slow） |

## Limit=10 且日志几乎相同怎么办

不要把 Limit 加到 50/200。重复刷屏（如同一句 Redis PING 超时）加条数不会增加新证据，只会占满上下文。

1. 先用 10 条看是否同一异常类 / 同一句话
2. 若高度重复：再查一次 `ERROR \| SELECT count(*) AS cnt`（或直方图）得到**量级**；从已有 10 条里抽 **1 条**做代表，必要时对该条 `DescribeLogContext`
3. 需要区分多种根因时：换更具体的全文词（异常类名）各查 Limit=5，而不是加大 Limit 扫重复行
4. 报告写法：「近 15 分钟约 N 条同类 ERROR，样例如下」+ 1 条原文，不要粘贴 10 条几乎一样的日志

## 空结果

- 近窗口内没有报错时，SearchLog 返回 0 条是正常现象，不要编造日志
- 可扩大到近 1 小时再查一次
- 仍为空则如实反馈，不要假装查到了内容
