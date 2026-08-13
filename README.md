# 智守

> 智能运维助手：知识库检索、多轮问答与自动告警分析

## 📖 项目简介

智能运维助手，包含三大核心模块：

### 1. RAG 知识库
将运维文档切片入库，问答时按需检索相关片段，避免把整库塞进上下文。启动只处理尚未入库的新文件。

### 2. 智能问答
多轮流式对话，可选 DeepSeek 或通义千问。能查时间、告警、日志和知识库；查日志时按需加载操作手册。支持近期对话、重命名，以及「有价值」标记。闲聊不提炼经验，排障对话可自动沉淀。

### 3. AIOps 告警分析
一键拉取当前告警，结合日志与知识库做根因分析，输出《告警分析报告》，分析成功后沉淀经验，供后续问答复用。

## 🚀 核心特性

- ✅ **RAG 知识库**: 增量入库、向量检索与重排
- ✅ **智能问答**: 流式多轮、模型切换、工具调用、近期对话与「有价值」标记
- ✅ **AIOps 告警分析**: 告警/日志联动，多 Agent 根因分析并输出报告
- ✅ **项目 Skill**: 长流程手册按需加载，不塞进每轮系统提示词
- ✅ **记忆系统**: 滚动摘要、情景记忆、经验沉淀与复用
- ✅ **会话管理**: 历史持久化、重命名、首轮回复后自动出现在近期对话


## 🛠️ 技术栈

| 技术 | 说明 |
|------|------|
| Java / Spring Boot | 应用框架 |
| Spring AI Alibaba | AI Agent 编排 |
| DeepSeek / 通义千问 | 对话模型 |
| DashScope | Embedding、重排 |
| Milvus | 向量数据库 |
| MySQL | 会话与经验数据 |
| Prometheus | 告警数据源 |
| 腾讯云 CLS | 日志查询（MCP） |

## 📦 核心模块

```
SuperBizAgent/
├── src/main/java/org/example/
│   ├── controller/     # 对外接口
│   ├── service/        # 对话、AIOps、记忆、经验、向量
│   ├── agent/tool/     # Agent 工具
│   └── config/         # 配置
├── src/main/resources/
│   ├── skills/         # Agent Skill 手册
│   └── static/         # Web 界面
├── scripts/            # 辅助脚本
├── docs/               # 功能文档
└── aiops-docs/         # 运维文档库
```


## ⚙️ 核心配置

### application.yml

复制 `application-example.yml` 为 `application.yml` 并填入真实配置（含 API Key，勿提交 Git）：

```bash
cp src/main/resources/application-example.yml src/main/resources/application.yml
```

主要配置项：

```yaml
milvus:
  host: 192.168.150.101   # Milvus 主机
  port: 19530

spring:
  datasource:
    url: jdbc:mysql://192.168.150.101:3306/superbiz_agent?...
    username: root
    password: ""
  ai:
    dashscope:
      api-key: your-dashscope-api-key

prometheus:
  base-url: http://192.168.150.101:9090

llm:
  default-provider: deepseek          # 未传 Provider 时的默认模型
  dashscope:
    chat-model: qwen-plus
  deepseek:
    chat-model: deepseek-v4-flash
    thinking: false
```

记忆与经验相关配置（窗口大小、滚动摘要、情景记忆、recency 衰减、弱经验晋升阈值等）见 `application-example.yml` 中 `memory.*` 与 `experience.*` 段的注释说明。

## 📚 功能文档

各功能的设计动机、架构与验证方法见 `docs/` 目录：

- 《更新日志-经验沉淀》- 会话持久化与经验分级沉淀、三层召回
- 《更新日志-情景记忆与滚动摘要》- 原生多轮消息、summary buffer、跨会话检索
- 《更新日志-经验闭环与记忆增强》- recency 因子、经验合并更新、弱经验晋升、Agent 主动记忆、AIOps 失败闭环
- 《更新日志-多文件类型知识库向量化》《更新日志-文档重排》- RAG 知识库链路
