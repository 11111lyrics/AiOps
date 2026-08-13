# Redis 不可用告警处理方案

## 告警名称
- **告警名**: `RedisDown`
- **告警级别**: 紧急
- **触发条件**: `redis_up == 0` 持续 1 分钟（redis_exporter 无法连接 Redis）

## 问题描述
Redis 在 tjxt 中用于缓存、Session、JWT refresh-token（JTI）等，宕机后会导致：
- 登录态/Token 刷新异常
- 缓存穿透，数据库压力骤增
- 多服务出现 `RedisConnectionFailureException`
- 网关、auth 等依赖 Redis 的服务错误率上升

## 环境说明

- **Region**: `ap-chengdu`
- **Redis 地址**: `192.168.150.101:6379`（与 tjxt Nacos `tj.redis.host` 一致）
- **指标来源**: Prometheus `redis_up`（redis_exporter）
- **影响范围**: 全部 14 个微服务（均引用 `shared-redis.yaml`）
- **优先查日志主题**:
  - `tjxt-dev-auth-service-log-ap-chengdu`（JWT、refresh-token、JTI 缓存）
  - `tjxt-dev-gateway-service-log-ap-chengdu`（网关缓存/限流相关）
  - `tjxt-dev-user-service-log-ap-chengdu`（用户信息缓存）

## 排查步骤

### 步骤1: 获取当前时间
**工具**: `getCurrentDateTime`

### 步骤2: 查询 Prometheus 告警
**工具**: `queryPrometheusAlerts`
- 确认 `RedisDown` 为 firing

### 步骤3: 确认 Redis 进程与端口
在主机 B 执行：
```bash
docker ps | grep -i redis
ss -lntp | grep 6379
redis-cli -h 127.0.0.1 -p 6379 ping
```

### 步骤4: 查询 CLS ERROR 日志
**工具**: MCP `TextToSearchLogQuery` → `SearchLog`
- **Region**: `ap-chengdu`
- **时间**: 近 15 分钟
- **CQL 示例**:
  ```
  ERROR AND (Redis OR Redisson OR RedisConnectionFailureException OR "Connection reset" OR READONLY)
  NOT (BadRequestException OR "自定义异常" OR "请求参数校验")
  ```
- **建议主题**: auth-service、gateway-service、user-service

### 步骤5: 查看异常栈上下文
**工具**: MCP `DescribeLogContext`
- 重点查看 Redisson/Jedis/Lettuce 相关栈，确认是连接失败还是主从切换

### 步骤6: 检索内部文档
**工具**: `queryInternalDocs`
**关键词**: `RedisDown RedisConnectionFailureException Redis`

## 常见原因分析

### 原因1: Redis 容器/进程停止
**特征**:
- `6379` 无监听
- `redis_up == 0`
- 多服务同时 Redis 连接失败

**处理方案**:
1. `docker start <redis容器名>`
2. `redis-cli ping` 返回 `PONG`
3. 观察 `redis_up` 与 `RedisDown` 告警恢复

### 原因2: Redis 内存满（maxmemory 淘汰或拒绝写入）
**特征**:
- Redis 进程存活但部分命令失败
- 日志可能有 `OOM command not allowed` 或写入异常
- 可能与 `HighMemoryUsage` 相关

**处理方案**:
1. `redis-cli INFO memory` 查看使用率
2. 清理非关键 key 或扩容 maxmemory
3. 检查是否有大 key 或未设置 TTL 的缓存

### 原因3: 主从切换或 READONLY 模式
**特征**:
- 日志含 `READONLY You can't write`
- 刚发生过主节点故障

**处理方案**:
1. 确认当前主节点角色 `redis-cli INFO replication`
2. 修复主从拓扑或提升从节点
3. 应用侧重连 Redis

### 原因4: 密码或网络配置变更
**特征**:
- exporter 与应用同时连不上
- 近期修改过 Redis 密码或 Docker 网络

**处理方案**:
1. 核对 Nacos `shared-redis.yaml` 与应用配置
2. 更新 redis_exporter 的 `REDIS_PASSWORD`（如有）
3. 验证容器网络 `heima-net` 内互通

### 原因5: 连接数或文件描述符耗尽
**特征**:
- Redis 存活但新连接被拒绝
- 错误日志含 `max number of clients reached`

**处理方案**:
1. `redis-cli CLIENT LIST` 统计连接
2. 调整 `maxclients` 或重启泄漏连接的应用实例
3. 排查连接池未释放

## 紧急处理流程

### 第一时间（1 分钟内）
1. 确认 `RedisDown` 与 `redis_up==0`
2. 评估用户登录/Token 是否大面积失效

### 5 分钟内
1. 重启 Redis 容器
2. 若 Redis 正常但 exporter 异常，检查 exporter 配置与网络
3. 观察告警是否恢复

### 15 分钟内
1. Redis 恢复后，重启 **auth-service** 与 **gateway-service**（优先）
2. 抽测登录、Token 刷新
3. CLS 确认无新增 Redis 连接 ERROR

## 验证步骤
1. `redis_up == 1`，`RedisDown` 告警消失
2. `redis-cli ping` 正常
3. auth-service 日志无新增 `RedisConnectionFailureException`
4. 经网关完成一次登录 + refresh 流程
5. 持续观察 30 分钟

## 预防措施
1. Redis 持久化与备份策略（RDB/AOF 按业务要求）
2. 监控内存、连接数、命中率
3. 缓存 key 规范 TTL，避免内存撑满
4. 主从或 Sentinel 高可用（生产环境）

## 相关告警
- `ServiceUnavailable`: auth 等依赖 Redis 的服务探活失败
- `HighMemoryUsage`: Redis 或宿主机内存过高
- `MySQLDown`: Redis 宕机后缓存失效可能导致 DB 压力上升

## 参考文档
- [Spring Boot 常见日志错误模式](spring_boot_error_patterns.md)
- [网关与鉴权服务错误排查](gateway_auth_errors.md)
- [tjxt 微服务架构与运维排查概览](tjxt_microservices_overview.md)
- [CLS 日志查询指南](cls_log_query_guide.md)
