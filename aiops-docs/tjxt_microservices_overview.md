# tjxt 微服务架构与运维排查概览

## 系统架构

```
客户端 → gateway-service → auth-service（鉴权）
                ↓
    user / course / trade / pay / exam / learning / media / message / search / data / promotion / remark
```

- **gateway-service**：统一入口、路由、限流
- **auth-service**：登录、Token、权限
- **业务服务**：各自独立 spring.log，CLS 分主题采集

## 基础设施

| 组件 | 地址 | 用途 |
|------|------|------|
| Prometheus | http://192.168.150.101:9090 | 告警与指标 |
| MySQL | 192.168.150.101:3306 | 业务数据库 |
| Milvus | 192.168.150.101:19530 | RAG 向量库 |
| 日志采集机 | 192.168.150.101 | LogListener + 微服务部署 |

## 全量日志主题（ap-chengdu）

| 服务 | 日志主题 | 典型告警 |
|------|----------|----------|
| gateway-service | tjxt-dev-gateway-service-log-ap-chengdu | 502/503、路由失败 |
| auth-service | tjxt-dev-auth-service-log-ap-chengdu | 登录失败、Token 过期 |
| user-service | tjxt-dev-user-service-log-ap-chengdu | 用户信息异常 |
| course-service | tjxt-dev-course-service-log-ap-chengdu | 课程接口错误 |
| trade-service | tjxt-dev-trade-service-log-ap-chengdu | 订单异常 |
| pay-service | tjxt-dev-pay-service-log-ap-chengdu | 支付失败 |
| exam-service | tjxt-dev-exam-service-log-ap-chengdu | 考试模块错误 |
| learning-service | tjxt-dev-learning-service-log-ap-chengdu | 学习进度异常 |
| media-service | tjxt-dev-media-service-log-ap-chengdu | 音视频上传/转码 |
| message-service | tjxt-dev-message-service-log-ap-chengdu | 消息推送失败 |
| search-service | tjxt-dev-search-service-log-ap-chengdu | 搜索超时 |
| data-service | tjxt-dev-data-service-log-ap-chengdu | 数据统计异常 |
| promotion-service | tjxt-dev-promotion-service-log-ap-chengdu | 营销活动错误 |
| remark-service | tjxt-dev-remark-service-log-ap-chengdu | 评论/评价异常 |

## 标准排查流程

1. **queryPrometheusAlerts**：确认活跃告警与服务名
2. **queryInternalDocs**：检索对应告警处理文档
3. **CLS MCP**：按服务查 ERROR 日志（见 cls_log_query_guide.md）
4. **DescribeLogContext**：展开异常栈
5. 输出根因与处理建议

## 服务依赖排查顺序

| 现象 | 建议查询顺序 |
|------|-------------|
| 全站不可用 | gateway → auth → 下游首个 5xx 服务 |
| 单接口失败 | 对应业务服务 → 其日志中的 Feign/HTTP 调用目标 |
| 支付失败 | pay-service → trade-service |
| 登录失败 | auth-service → user-service |
