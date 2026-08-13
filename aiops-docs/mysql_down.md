# MySQL 不可用告警处理方案

## 告警名称
- **告警名**: `MySQLDown`
- **告警级别**: 紧急
- **触发条件**: `mysql_up == 0` 持续 1 分钟（mysql_exporter 无法连接 MySQL）

## 问题描述
MySQL 是 tjxt 全部业务服务的核心依赖，宕机后会导致：
- 几乎所有微服务数据库读写失败
- 登录、下单、支付等核心链路中断
- 多服务同时出现 `SQLException`、`Communications link failure`
- 可能引发连接池耗尽与级联超时

## 环境说明

- **Region**: `ap-chengdu`
- **MySQL 地址**: `192.168.150.101:3306`（与 tjxt Nacos `tj.jdbc.host` 一致）
- **指标来源**: Prometheus `mysql_up`（mysql_exporter）
- **影响范围**: 除 gateway 外，绝大多数业务微服务
- **优先查日志主题**（多服务并发 ERROR，建议并行检索）:
  - `tjxt-dev-auth-service-log-ap-chengdu`
  - `tjxt-dev-user-service-log-ap-chengdu`
  - `tjxt-dev-trade-service-log-ap-chengdu`
  - `tjxt-dev-gateway-service-log-ap-chengdu`（502/503 或下游超时）

## 排查步骤

### 步骤1: 获取当前时间
**工具**: `getCurrentDateTime`

### 步骤2: 查询 Prometheus 告警
**工具**: `queryPrometheusAlerts`
- 确认 `MySQLDown` 为 firing，记录 `active_at` 与持续时间

### 步骤3: 确认 MySQL 进程与端口
在主机 B（tjxt 部署机）执行：
```bash
docker ps | grep -i mysql
ss -lntp | grep 3306
mysqladmin -h 127.0.0.1 -P 3306 -u root -p ping
```

### 步骤4: 查询多服务 ERROR 日志（CLS）
**工具**: MCP `TextToSearchLogQuery` → `SearchLog`
- **Region**: `ap-chengdu`
- **时间**: 告警触发前后 15 分钟
- **CQL 示例**:
  ```
  ERROR AND (SQLException OR "Communications link failure" OR "mysql数据库操作异常" OR HikariPool)
  NOT (BadRequestException OR "自定义异常" OR "请求参数校验")
  ```
- **建议主题**: auth-service、user-service、trade-service、course-service（任选 2～4 个代表服务）

### 步骤5: 查看异常栈上下文
**工具**: MCP `DescribeLogContext`
- 对含 `Communications link failure` 或 `mysql数据库操作异常` 的日志展开上下文，确认是否为 MySQL 不可达而非单条 SQL 错误

### 步骤6: 检索内部文档
**工具**: `queryInternalDocs`
**关键词**: `MySQLDown Communications link failure SQLException`

## 常见原因分析

### 原因1: MySQL 容器/进程停止
**特征**:
- `3306` 端口无监听
- `mysql_up == 0`
- 所有服务几乎同时报连接失败

**处理方案**:
1. `docker start <mysql容器名>` 或 `systemctl start mysqld`
2. 确认启动日志无报错
3. 验证 `mysqladmin ping` 返回 alive

### 原因2: MySQL OOM 或被系统 Kill
**特征**:
- 容器状态 `Exited (137)`
- 系统日志有 OOM Killer 记录
- 故障前可能有慢查询或大批量导入

**处理方案**:
1. 重启 MySQL
2. 检查 `innodb_buffer_pool_size` 与宿主机内存
3. 排查是否有异常大事务或全表扫描

### 原因3: 连接数耗尽（Too many connections）
**特征**:
- MySQL 进程存活但新连接被拒绝
- 日志关键词 `Too many connections`
- 通常伴随连接池泄漏或流量突增

**处理方案**:
1. `SHOW PROCESSLIST;` 查看连接占用
2. 临时调大 `max_connections`（需评估内存）
3. 重启高占用连接的应用实例释放连接池
4. 排查慢 SQL 与未关闭连接

### 原因4: 磁盘满导致无法写入
**特征**:
- MySQL 错误日志有 `No space left on device`
- 与 `HighDiskUsage` 告警可能同时出现

**处理方案**:
1. 清理 binlog、慢查询日志、临时文件
2. 扩容磁盘
3. 重启 MySQL 并验证

### 原因5: 网络或防火墙变更
**特征**:
- MySQL 本机可连，容器内 exporter/应用不可连
- 近期有安全组或 iptables 变更

**处理方案**:
1. 从业务容器网络测试 `telnet <mysql_host> 3306`
2. 恢复防火墙规则
3. 确认 Docker 网络 `heima-net` 正常

## 紧急处理流程

### 第一时间（1 分钟内）
1. 确认 `MySQLDown` 告警与 `mysql_up==0`
2. 通知 DBA/运维值班
3. 评估是否启用只读降级或维护公告（全站写操作不可用）

### 5 分钟内
1. 尝试重启 MySQL 容器/服务
2. 若无法启动，查看 MySQL error log 定位原因
3. 同步观察 Prometheus 告警是否恢复

### 15 分钟内
1. MySQL 恢复后，**按依赖顺序重启**受影响严重的业务服务（auth → user → trade 等），重建连接池
2. CLS 复查 ERROR 是否停止增长
3. 网关抽测核心接口

## 验证步骤
1. `mysql_up == 1`，`MySQLDown` 告警消失
2. `mysqladmin ping` 正常
3. 任选 auth-service、user-service CLS 主题，近 5 分钟无新增 `Communications link failure`
4. 经网关测试登录或简单读接口成功
5. 持续观察 30 分钟

## 预防措施
1. MySQL 主从或定期备份，制定 RPO/RTO
2. 监控连接数、慢查询、磁盘使用率
3. 应用侧合理配置 Hikari 连接池上限与超时
4. 定期演练 MySQL 故障切换与恢复

## 相关告警
- `ServiceUnavailable`: 下游服务因 DB 不可用连带 TCP 探活失败
- `HighDiskUsage`: 磁盘满导致 MySQL 异常
- `SlowResponse`: DB 慢查询导致响应变慢（MySQL 未完全宕机时）

## 参考文档
- [Spring Boot 常见日志错误模式](spring_boot_error_patterns.md)
- [服务不可用告警处理方案](service_unavailable.md)
- [tjxt 微服务架构与运维排查概览](tjxt_microservices_overview.md)
- [CLS 日志查询指南](cls_log_query_guide.md)
