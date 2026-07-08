# 网关与鉴权服务错误排查

## 适用场景

- 全站 502/503/504
- 登录失败、401/403
- Token 无效、鉴权拦截
- 路由找不到、服务未注册

## 涉及服务与日志主题

| 服务 | 日志主题 | Region |
|------|----------|--------|
| gateway-service | tjxt-dev-gateway-service-log-ap-chengdu | ap-chengdu |
| auth-service | tjxt-dev-auth-service-log-ap-chengdu | ap-chengdu |

## 排查步骤

### 步骤1: 查询 Prometheus 告警
**工具**: `queryPrometheusAlerts`

### 步骤2: 查询网关 ERROR 日志
**工具**: MCP `TextToSearchLogQuery` → `SearchLog`
- **Region**: `ap-chengdu`
- **TopicId / 主题名**: `tjxt-dev-gateway-service-log-ap-chengdu`
- **时间**: 近 15 分钟
- **CQL 示例**: `ERROR OR 503 OR 504 OR "Connection refused" OR "LoadBalancer"`

### 步骤3: 查询鉴权服务日志
- **主题**: `tjxt-dev-auth-service-log-ap-chengdu`
- **CQL 示例**: `ERROR OR 401 OR 403 OR "Invalid token" OR "JWT"`

### 步骤4: 查看异常上下文
**工具**: MCP `DescribeLogContext`

## 常见根因

### 网关 503 / 无可用实例
- 下游微服务未启动或未注册到注册中心
- 处理：查下游服务日志与健康检查，重启异常实例

### 401 / Token 过期
- auth-service 时钟偏移或 Redis 中 session 失效
- 处理：查 auth 日志中的 `JwtException`、`RedisConnectionException`

### 路由 404
- gateway 路由配置与现网服务名不一致
- 处理：查 gateway 日志 `RouteDefinition` 相关 WARN

## 处理建议优先级

1. 确认 gateway、auth 进程存活
2. CLS 查 ERROR + DescribeLogContext 定位栈
3. 修复下游服务或配置后观察告警恢复
