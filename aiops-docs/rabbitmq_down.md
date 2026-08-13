# RabbitMQ 不可用告警处理方案

## 告警名称
- **告警名**: `RabbitMQDown`
- **告警级别**: 紧急
- **触发条件**: Blackbox TCP 探测 `5672` 失败，`probe_success == 0` 持续 1 分钟

## 问题描述
RabbitMQ 是 tjxt 异步消息与事件驱动的基础组件，宕机后会导致：
- 消息发送/消费失败，订单、支付、通知等异步链路中断
- 日志出现 `AmqpException`、`Failed to connect to broker`
- message-service 及依赖 MQ 的业务服务 ERROR 增多
- 消息堆积在应用侧或丢失（取决于生产者重试策略）

## 环境说明

- **Region**: `ap-chengdu`
- **RabbitMQ 地址**: `192.168.150.101:5672`（与 tjxt Nacos `tj.mq.host` 一致）
- **管理端口**（若开启）: `15672`
- **指标来源**: Prometheus Blackbox Exporter（TCP 探 5672）
- **影响范围**: message-service 及所有使用 Spring AMQP 的生产者/消费者
- **优先查日志主题**:
  - `tjxt-dev-message-service-log-ap-chengdu`
  - `tjxt-dev-trade-service-log-ap-chengdu`（订单/支付异步）
  - `tjxt-dev-pay-service-log-ap-chengdu`
  - `tjxt-dev-gateway-service-log-ap-chengdu`（若入口同步转异步失败）

## 排查步骤

### 步骤1: 获取当前时间
**工具**: `getCurrentDateTime`

### 步骤2: 查询 Prometheus 告警
**工具**: `queryPrometheusAlerts`
- 确认 `RabbitMQDown` 为 firing

### 步骤3: 确认 RabbitMQ 进程与端口
在主机 B 执行：
```bash
docker ps | grep -i rabbit
ss -lntp | grep 5672
# 若安装了 rabbitmqctl：
docker exec <rabbitmq容器> rabbitmqctl status
```

### 步骤4: 查询 CLS ERROR 日志
**工具**: MCP `TextToSearchLogQuery` → `SearchLog`
- **Region**: `ap-chengdu`
- **时间**: 近 15 分钟
- **CQL 示例**:
  ```
  ERROR AND (AmqpException OR Rabbit OR "Failed to connect" OR "Connection refused" OR "broker" OR 5672)
  NOT (BadRequestException OR "自定义异常" OR "请求参数校验")
  ```
- **建议主题**: message-service、trade-service、pay-service

### 步骤5: 查看异常栈上下文
**工具**: MCP `DescribeLogContext`
- 确认是 Broker 不可达还是队列/交换机配置问题

### 步骤6: 检索内部文档
**工具**: `queryInternalDocs`
**关键词**: `RabbitMQDown AmqpException message-service`

## 常见原因分析

### 原因1: RabbitMQ 容器/进程停止
**特征**:
- `5672` 无监听
- `probe_success == 0`
- message-service 连接 Broker 失败

**处理方案**:
1. `docker start <rabbitmq容器名>`
2. 等待节点完全启动（管理界面或 `rabbitmqctl status` 正常）
3. 确认 `RabbitMQDown` 告警恢复

### 原因2: 磁盘或内存告警导致 RabbitMQ 阻塞
**特征**:
- 进程存活但拒绝连接或阻塞发布
- RabbitMQ 日志有 `resource alarm`、`disk free limit`
- 可能与 `HighDiskUsage` / `HighMemoryUsage` 同时出现

**处理方案**:
1. 清理磁盘或扩容
2. `rabbitmqctl set_vm_memory_high_watermark`（按规范调整）
3. 重启 RabbitMQ 并检查队列堆积

### 原因3: 网络或 Docker 网络隔离
**特征**:
- 宿主机可连 5672，业务容器不可连
- 近期调整过 `heima-net` 或端口映射

**处理方案**:
1. 从业务容器内 `telnet <mq_host> 5672`
2. 修复 Docker 网络与端口映射
3. 重启受影响消费者服务

### 原因4: 认证或 vhost 配置错误
**特征**:
- TCP 可达但 AMQP 握手/认证失败
- 日志含 `ACCESS_REFUSED`、`authentication failure`

**处理方案**:
1. 核对 Nacos 中 MQ 用户名、密码、virtual-host
2. RabbitMQ 管理界面检查用户权限
3. 修正配置后滚动重启消费者

### 原因5: 队列堆积导致消费者 OOM 或反复重连
**特征**:
- Broker 正常但消费端频繁 ERROR
- 故障恢复后需处理大量堆积消息

**处理方案**:
1. 恢复 Broker 后观察队列深度
2. 必要时限流消费或扩容消费者实例
3. 对过期消息做 DLQ/丢弃策略（按业务规则）

## 紧急处理流程

### 第一时间（1 分钟内）
1. 确认 `RabbitMQDown` 告警
2. 评估是否有支付/订单等关键异步链路受影响

### 5 分钟内
1. 重启 RabbitMQ
2. 检查管理界面节点状态为 running
3. 观察 Blackbox `probe_success` 恢复为 1

### 15 分钟内
1. 优先重启 **message-service**，再重启 trade-service、pay-service 等强依赖 MQ 的服务
2. 检查核心队列是否有严重堆积
3. CLS 确认无新增 Amqp 连接 ERROR

## 验证步骤
1. Blackbox 探 5672 成功，`RabbitMQDown` 消失
2. `rabbitmqctl status` 或管理 UI 显示节点正常
3. message-service 日志无新增连接 Broker 失败
4. 触发一条测试消息（或走一笔会发 MQ 的业务）验证收发
5. 持续观察 30 分钟

## 预防措施
1. RabbitMQ 集群或镜像队列（生产环境）
2. 监控队列深度、消费速率、磁盘/内存 alarm
3. 生产者确认机制与死信队列（DLQ）
4. 定期演练 Broker 重启与消息堆积处理

## 相关告警
- `ServiceUnavailable`: message-service 等进程连带不可用
- `MySQLDown` / `RedisDown`: 中间件连锁故障
- `HighDiskUsage`: 磁盘满导致 RabbitMQ 阻塞

## 参考文档
- [Spring Boot 常见日志错误模式](spring_boot_error_patterns.md)
- [服务不可用告警处理方案](service_unavailable.md)
- [tjxt 微服务架构与运维排查概览](tjxt_microservices_overview.md)
- [CLS 日志查询指南](cls_log_query_guide.md)
