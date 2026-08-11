# SuperBizAgent

> 基于 Spring Boot + AI Agent 的智能问答与运维系统

## 📖 项目简介

企业级智能业务代理系统，包含三大核心模块：

### 1. RAG 智能问答
集成 Milvus 向量数据库和阿里云 DashScope，提供基于检索增强生成的智能问答能力，支持多轮对话和流式输出。

### 2. AIOps 智能运维
基于 AI Agent 的自动化运维系统，采用 Planner-Executor-Replanner 架构，实现告警分析、日志查询、智能诊断和报告生成。

### 3. 记忆系统
分层记忆体系：短期记忆（原生多轮消息 + 滚动摘要）、情景记忆（历史会话向量归档、跨会话检索）、经验沉淀（分级触发、recency 加权召回、合并更新、弱经验晋升、置信度衰减遗忘），并支持 Agent 主动读写长期记忆。

## 🚀 核心特性

- ✅ **RAG 问答**: 向量检索 + 重排 + 多轮对话 + 流式输出
- ✅ **AIOps 运维**: 智能诊断 + 多 Agent 协作 + 自动报告 + 经验评分闭环
- ✅ **记忆系统**: 滚动摘要 + 情景记忆 + 经验沉淀复用 + Agent 主动记忆（saveMemory/searchMemory）
- ✅ **工具集成**: 文档检索、告警查询、日志分析（MCP/CLS）、历史会话检索、时间工具
- ✅ **会话管理**: MySQL 持久化、上下文维护、前后端删除同步
- ✅ **Web 界面**: 提供测试界面和 RESTful API


## 🛠️ 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Java | 17 | 开发语言 |
| Spring Boot | 3.2.0 | 应用框架 |
| Spring AI Alibaba | 1.1.0.0-RC2 | AI Agent 框架（ReactAgent / SupervisorAgent） |
| DashScope | 2.17.0 | 阿里云 AI 服务（对话 / Embedding / Rerank） |
| Milvus | 2.6.10 | 向量数据库（biz / experience / episodic 三集合） |
| MySQL | 8.x | 会话历史、滚动摘要、经验元数据 |

## 📦 核心模块

```
SuperBizAgent/
├── src/main/java/org/example/
│   ├── controller/
│   │   ├── ChatController.java        # 统一接口控制器 ⭐
│   │   ├── ExperienceController.java  # 经验标记/评分接口
│   │   └── FileUploadController.java  # 文档上传
│   ├── service/
│   │   ├── ChatService.java           # 对话服务（原生多轮消息） ⭐
│   │   ├── AiOpsService.java          # AIOps 多 Agent 编排 ⭐
│   │   ├── ChatMemoryService.java     # 短期记忆（MySQL 会话历史）
│   │   ├── ConversationSummaryService.java # 滚动摘要（超窗口历史压缩）
│   │   ├── EpisodicMemoryService.java # 情景记忆（历史会话归档/检索）
│   │   ├── ExperienceService.java     # 经验沉淀/召回/合并更新 ⭐
│   │   ├── ExperienceLifecycleService.java # 经验评分/衰减/晋升/遗忘
│   │   ├── RagService.java            # 独立 RAG 服务（未接线，保留备用）
│   │   └── Vector*.java               # 向量服务
│   ├── agent/tool/                    # Agent 工具集
│   │   ├── DateTimeTools.java         # 时间工具
│   │   ├── InternalDocsTools.java     # 文档检索
│   │   ├── QueryMetricsTools.java     # 告警查询
│   │   ├── QueryLogsTools.java        # 日志查询（Mock 模式）
│   │   ├── EpisodicMemoryTools.java   # 历史会话检索（searchPastConversations）
│   │   └── AgentMemoryTools.java      # 主动记忆读写（saveMemory/searchMemory）
│   └── config/                        # 配置类
├── src/main/resources/
│   ├── static/                        # Web 界面
│   ├── schema.sql                     # MySQL 建表（启动自动执行）
│   └── application.yml                # 应用配置
├── docs/                              # 各功能更新日志
└── aiops-docs/                        # 运维文档库
```


## 📡 核心接口

### 1. 智能问答接口

**流式对话（推荐）**
```bash
POST /api/chat_stream
Content-Type: application/json

{
  "Id": "session-123",
  "Question": "什么是向量数据库？"
}
```
支持 SSE 流式输出、自动工具调用、多轮对话。

**普通对话**
```bash
POST /api/chat
Content-Type: application/json

{
  "Id": "session-123",
  "Question": "什么是向量数据库？"
}
```
一次性返回完整结果，支持工具调用和多轮对话。响应体携带服务端实际使用的 `sessionId`（未传 `Id` 时由服务端生成并回传，便于续接会话）。

> 对话历史以原生多轮消息传给 Agent；超出窗口（默认 6 对）的早期内容自动压缩为滚动摘要注入；每轮问答异步归档进情景记忆，Agent 可通过 `searchPastConversations` 工具跨会话回忆。

### 2. AIOps 智能运维接口

```bash
POST /api/ai_ops
```
自动执行告警分析流程，生成运维报告（SSE 流式输出）。分析前召回历史经验注入，报告成功后自动正反馈并沉淀新经验；失败路径对本次采纳经验回写负反馈。

### 3. 会话管理

- `POST /api/chat/clear` - 清空会话历史（含滚动摘要，前端删除会话时自动调用）
- `GET /api/chat/session/{sessionId}` - 获取会话信息

### 4. 经验管理

- `POST /api/experience/mark` - 人工标记会话有价值，强制提炼沉淀（请求体 `{"sessionId": "..."}`）
- `POST /api/experience/feedback` - 经验评分反馈（请求体 `{"expId": "...", "success": true}`），成功评分可使弱经验晋升

### 5. 文件管理

- `POST /api/upload` - 上传文件并自动向量化（txt/md/markdown/pdf/doc/docx，单文件 ≤ 50MB）
- `GET /milvus/health` - Milvus 健康检查


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
```

记忆与经验相关配置（窗口大小、滚动摘要、情景记忆、recency 半衰期、弱经验晋升阈值等）见 `application-example.yml` 中 `memory.*` 与 `experience.*` 段的注释说明。

## 📚 功能文档

各功能的设计动机、架构与验证方法见 `docs/` 目录：

- 《更新日志-经验沉淀》- 会话持久化与经验分级沉淀、三层召回
- 《更新日志-情景记忆与滚动摘要》- 原生多轮消息、summary buffer、跨会话检索
- 《更新日志-经验闭环与记忆增强》- recency 因子、经验合并更新、弱经验晋升、Agent 主动记忆、AIOps 失败闭环
- 《更新日志-多文件类型知识库向量化》《更新日志-文档重排》- RAG 知识库链路


## 🚀 快速开始

### 1. 环境准备

```bash
# 从示例复制并编辑配置
cp src/main/resources/application-example.yml src/main/resources/application.yml
```

### 2. 启动应用

方法一： 手动启动
```bash
1.先启动向量数据库
docker compose up -d -f vector-database.yml

2.启动服务
mvn clean install
mvn spring-boot:run
```

方法二：一键启动
```bash
make init  # 会自动启动向量数据库并上传运维文档到向量库
```


### 3. 使用示例

**Web 界面**
```
http://localhost:9900
```

**命令行**
```bash
# 上传文档
curl -X POST http://localhost:9900/api/upload \
  -F "file=@document.txt"

# 智能问答
curl -X POST http://localhost:9900/api/chat \
  -H "Content-Type: application/json" \
  -d '{"Id":"test","Question":"什么是向量数据库？"}'

# 健康检查
curl http://localhost:9900/milvus/health
```


**版本**: v1.0.0  
**作者**: chief  
**许可证**: MIT
