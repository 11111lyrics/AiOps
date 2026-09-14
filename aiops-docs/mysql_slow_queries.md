# MySQL 慢查询运维手册（tjxt）

## 告警名称
- **告警名**: `MySQLSlowQueries`
- **告警级别**: warning
- **触发条件**: `increase(mysql_global_status_slow_queries[2m]) >= 20` 持续 30 秒
- **含义**: 近 2 分钟至少新增 20 条慢查询（约每分钟 10 条），并且稳住 30 秒。偶发几条不会告。

这不是宕机。`mysql_up` 仍为 1，禁止当成 `MySQLDown`，禁止 `docker stop/restart mysql`。

## 环境（查自远端 tjxt，192.168.150.101 容器）

| 项 | 值 |
|---|---|
| MySQL | Docker 容器 `mysql`，8.0.27，root 密码在容器环境变量 `MYSQL_ROOT_PASSWORD` |
| 慢日志 | `slow_query_log=ON`，`long_query_time=1`，文件 `/var/lib/mysql/slow.log`，宿主机软链 `/data/tjxt/logs/mysql/slow.log` |
| CLS | 主题 `tjxt-dev-mysql-slow-log-ap-chengdu`，服务键 `mysql-slow`，TopicId `9cddac58-51e9-442a-acc9-3f9ac1b24380` |
| Prometheus | 本机 Agent 用 `http://10.195.86.203:9090`，不要用 `127.0.0.1` 或经验库虚机 `192.168.150.101:9090` |
| 业务库 | `tj_course` / `tj_trade` / `tj_pay` / `tj_user` / `tj_auth` / `tj_learning` 等，与微服务一一对应 |

## 实表与索引（information_schema / SHOW INDEX）

种子数据很小：`tj_course.course` 约 14 行，`course_catalogue` 约 168 行，`tj_trade.order` 约 16 行。表结构上 **`name` / `user_id` 等常用列没有二级索引**。课程量到数十万行后，按名检索或按名聚合会超过 `long_query_time=1`，进入慢日志。

不要把慢查询解释成 `SLEEP()`，也不要当成宕机。若 `SHOW INDEX` 已有 `idx_name`，不要重复 `ADD INDEX`。

### `tj_course.course`（课程，course-service / 网关 `/cs/**`）

现有索引：**只有 PRIMARY (`id`)**。  
未建索引的常用列：`name`、`status`、`first_cate_id`、`deleted`。  
线上课程名检索 / 按名统计会写成类似：

```sql
SELECT id, name, status FROM tj_course.course WHERE name LIKE 'Java%' AND deleted = 0;
SELECT name, COUNT(*) AS cnt FROM tj_course.course WHERE name LIKE 'Java%' GROUP BY name;
```

`LIKE 'Java%'`（前缀）和 `GROUP BY name` 都能走 `idx_name`。不要写成前导通配 `LIKE '%Java%'` 再建议普通 BTree 索引。

正确修复（L2 playbook `mysql-add-course-name-index`）：

```sql
ALTER TABLE tj_course.course ADD INDEX idx_name (name);
```

回滚：`ALTER TABLE tj_course.course DROP INDEX idx_name;`

### `tj_course.course_catalogue`（章节）

只有 PRIMARY (`id`)，**没有 `course_id` 索引**。按课拉目录会扫全表。需要时再人工评估 `ADD INDEX idx_course_id (course_id)`，当前目录没有对应自动 playbook。

### `tj_trade.order`（订单，表名是保留字）

只有 PRIMARY (`id`)，**没有 `user_id` 索引**。用户订单列表会扫全表。  
playbook `mysql-add-order-user-index`：

```sql
ALTER TABLE tj_trade.`order` ADD INDEX idx_user_id (user_id);
```

### 已有索引、不要乱加

`tj_promotion.exchange_code` 已有 `index_status(status)`、`index_config_id(exchange_target_id)`，不要重复建。

## 排查步骤（Agent）

### 1. 确认告警
工具：`queryPrometheusAlerts`  
看到 `MySQLSlowQueries` firing/pending。不要写成 MySQLDown。

### 2. CLS 定位 SQL
`loadSkill(cls-log-query)` → `TextToSearchLogQuery` → `SearchLog`  
主题：`tjxt-dev-mysql-slow-log-ap-chengdu`  
全文：`Query_time` / `Rows_examined` / `SELECT` / `tj_course.course`

看：哪条 SQL、`Query_time`、`Rows_examined` 对 `Rows_sent`。扫很多行只回几行 → 缺索引。

### 3. 对照业务日志（可选）
课程相关再查 `tjxt-dev-course-service-log-ap-chengdu`；订单查 `tjxt-dev-trade-service-log-ap-chengdu`。

### 4. 处置
只能引用 playbook 目录：

| id | 何时用 | 命令要点 |
|---|---|---|
| `mysql-add-course-name-index` | 慢 SQL 打在 `tj_course.course` / `name`（`LIKE 'Java%'` 或 `GROUP BY name`） | `ALTER TABLE tj_course.course ADD INDEX idx_name (name)` |
| `mysql-add-order-user-index` | 慢 SQL 打在 `tj_trade.order` / `user_id` | `ALTER TABLE tj_trade.\`order\` ADD INDEX idx_user_id (user_id)` |

风险都是 L2，须审批（`POST /api/incidents/{id}/approve`）。`remediation.enabled=true` 时批准后才会 SSH 执行；为 false 则节点 ⑤ 只 dry-run。报告里仍须写出上述命令。  
没有「重启 MySQL / 重启全部微服务」这种 playbook，禁止当首选。

## 常见误判

| 误判 | 正确 |
|---|---|
| MySQLDown / 进程没了 | 慢查询时进程在、`mysql_up=1` |
| 调大 `max_connections` | 那是连接打满（fw-05），不是本告警 |
| 只说「MySQL 好像慢」 | 必须给出 SQL 或表名 + 加哪一列索引 |
