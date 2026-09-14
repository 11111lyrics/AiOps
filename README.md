# 智守

智能 OnCall Agent。能力按五块组织：知识库、智能问答助手、上下文与记忆、一键告警分析、CLS 日志查询 Skill。

本地仓库名 `SuperBizAgent`。前端只打 HTTP；每次请求由后端现场组装 Agent（Java `@Tool` + CLS MCP），不是常驻进程。飞书说明：https://wcnw5ftm9m7e.feishu.cn/docx/ToRRd4mqho7IeKxpz3XcjDNGnSS

闭环：**手册入库 → 问答或一键分析取证 → 有用结论沉淀 → 下次召回**。经验只作参考，必须用当前告警和日志验证后再采纳。

```
前端
  → ChatController
      /api/chat、/api/chat_stream     智能问答助手
      /api/ai_ops                    一键告警分析
      /api/upload、/api/docs/sync    知识库
      /api/chat/attachments          当轮附件（不入知识库）
  → Milvus：手册 / 经验 / 情景记忆
  → MySQL：会话、滚动摘要、经验元数据
  → Prometheus 告警、腾讯云 CLS 日志
```

---

## 知识库

以 `aiops-docs/` 为唯一来源。问答和一键分析都不把整库塞进提示词，而是 Agent 调用 `queryInternalDocs` 按需检索。`RagService` 仍在仓库里（检索后把片段拼进提示词再流式生成），但没有 Controller 接线，**生产不走它**。

聊天附件走 `POST /api/chat/attachments`：落到 `uploads/chat-attachments/{sessionId}/`，只用基线抽文本拼进当轮用户消息，**不调远程解析、不入 Milvus、不走下面这条 RAG 链路**。拼进提示词时会明确告诉模型：不要用 `queryInternalDocs` 查刚上传的附件。

```
离线：文档加载 → 文档切割 → 向量化入库
在线：query 处理 → 向量检索（粗排）→ rerank（精排）→ 生成
```

### 离线阶段

四条路径都会进同一套离线索引，同步 internally 串行：启动扫描（`aiops.docs.auto-import`）；定时扫描（默认约 30 分钟，避开启动时刻）；`POST /api/docs/sync`（`force=true` 忽略指纹、全量重建）；`POST /api/upload`（写入 `aiops-docs` 后立刻索引该文件）。隐藏目录和路径里带 `chat-attachments` 的文件不进库。`GET /api/docs/parse` 可看解析开关、引擎和可达性。

#### 1. 文档加载

优先走远程版面解析，得到 Markdown；失败再回退基线提取。聊天附件不走这一步。

- **远程**：`DocumentParseService` 按配置选引擎。`paddle` 只接 PDF；`mineru` 接 PDF / DOCX。解析成功则整篇变成 Markdown。
- **基线**：远程关闭、引擎不支持该后缀、解析为空或调用失败，且 `fallback-to-baseline=true` 时走本地提取。MD / TXT 直接读文本；PDF 先按页（`PagePdfDocumentReader`），失败或 PDFBox 字体初始化失败再整篇 Tika；Word（`.doc` / `.docx`）走 Tika。关闭回退则该文件索引失败。
- **隔离集合**：`isolate-collection=true` 时，基线写 `biz`，MinerU 写 `biz_mineru`，Paddle 写 `biz_paddle`。检索只查**当前引擎对应的集合**，不跨集合合并。
- **增量**：磁盘有、库没有 → 加载入库；mtime / size / 解析器变了 → 删旧向量再加载；库有、磁盘没有 → 删向量。上次已回退且远程仍不可达时，下次 sync **不**反复全量重建。

#### 2. 文档切割

`document.chunk.max-size=800`，相邻片重叠 `overlap=100`。先按结构切开，超长再交给 `TextChunkHelper`：空行分段 → HanLP 按句末（。！？；，逗号不切）填满一片 → 单句仍超长才按字符硬切，切点带回重叠。

- 远程解析成功：一律 `MarkdownChunkStrategy`，按 `#`～`######` 标题成节；一节不超过 800 字整节一片，超过再按上面规则切。片上记下章节标题。
- 基线 MD：同样按标题切。
- 基线 TXT / Word：`PlainTextChunkStrategy`，不认标题，按段落 + 句子切。
- 基线 PDF：一页一个文本块，页内再切；元数据带页码，标题缺省为「第 N 页」。

#### 3. 向量化入库

每个分片单独调百炼 Embedding（`qwen3.7-text-embedding`），得到 **1024 维**向量，写入当前知识集合。不分批；一片失败则整文件索引失败。

Milvus 字段：`id` / `vector` / `content`（上限 8192，本项目片长 800 够用）/ `metadata` JSON。索引 **IVF_FLAT + L2**，`nlist=128`。`id` 由「绝对路径 `_source` + chunkIndex」生成稳定 UUID，重复入库可覆盖。元数据带文件名、后缀、mtime、size、片序号、章节标题或页码、`_parser` / `_parser_fallback`。上传后索引失败只打日志，HTTP 仍成功（文件已落盘，可靠下次 sync 补）。

### 在线阶段

`queryInternalDocs` 与调试接口 `POST /api/docs/search` 共用 `RetrievalService`。聊天经验召回**不走**这条链路（用用户原话，避免附件全文污染）。

#### 1. Query 处理

HyDE（默认开）：DeepSeek 按「写排障手册片段、不要问答格式」生成一段假想正文（约 400 token），**只用这段文字做后面的向量召回**。失败、空结果或不足 20 字 → 用用户原问题召回。精排仍用**用户原话**，不用假想文档。

#### 2. 向量检索（粗排）

把上一步得到的文本再 embed 成 1024 维，在当前知识集合上搜 L2 近邻，`nprobe=10`。召回条数：开 rerank 时取 `max(recall-top-k, top-k)`，默认 **10**；关 rerank 则直接取 `top-k`（默认 3）。无命中则后面精排、生成都拿到空列表。

#### 3. Rerank（精排）

百炼 `qwen3-rerank`：query 是用户原话，documents 是粗排候选正文，截断 **top-k=3**。关 rerank、候选只有 1 条、接口失败或返回空 → **按粗排原顺序截断**，不中断问答。

#### 4. 生成

没有独立的「检索完立刻拼上下文再调一次 LLM」入口。工具把精排后的片段（内容、分数、元数据）以 JSON 交回 ReactAgent / 一键排障 RCA Agent，由对话模型或 RCA 结合告警、日志、经验再写回答。无命中返回 `no_results` JSON，工具抛错返回 `error` JSON，**对话继续**，模型自行改查 Prometheus / CLS / 经验。仓库里的 `RagService` 才是「参考资料 + 问题 → 流式生成」那条死代码。

### 失败兜底

| 失败 | 怎么兜 |
|------|--------|
| 远程解析失败 / 引擎不支持该格式 | 允许回退时走基线提取；关闭回退则该文件索引失败 |
| PDF 按页提取或 PDFBox 字体初始化失败 | 回退 Tika 整文提取 |
| 上次已回退、远程解析仍不可达 | 下次 sync **不**反复全量重建 |
| 启动扫描抛错 | 只打日志，**不阻止应用启动** |
| 上传后索引失败 | HTTP 仍成功，文件已在 `aiops-docs`，可靠下次 sync 补 |
| 单文件同步失败 | 记入失败列表，其余文件继续 |
| HyDE 失败或生成过短 | 用用户原问题做向量召回 |
| 百炼重排失败、空结果或候选只有 1 条 | 按向量原始顺序截断 |
| 知识库无命中 / 检索抛错 | 工具返回 `no_results` 或 `error` JSON，对话继续 |

### 评估

题集 `eval/rag-eval-set.json`（约 50 题，语料就是 `aiops-docs`）。调试接口 `POST /api/docs/search` 与生产 `queryInternalDocs` 同一条 `RetrievalService`，多返回粗召回 10 条，方便算分。金标按 `_file_name` 匹配，不用本机绝对路径。聊天附件不进这套评测。

只评检索时看三件事：

- **Hit@3**：精排 Top 3 里是否命中金标文件，且正文含金标片段
- **Recall@10**：粗召回 10 条里是否出现金标文件
- **MRR**：第一条命中金标文件的排名倒数

生成侧另看：回答是否覆盖金标要点；不得把干扰文档当成主依据。

对比解析器时只改 `document.parse.engine`，embedding / 分片 / rerank 锁死。基线、Paddle、MinerU 写进不同集合。`parser_sensitive=false`（约 32 题）答案在正文和表格里，三套分数应接近；`true`（约 18 题）答案只在图里，基线 PDFBox/Tika 抽不到。Paddle 只解析 PDF，DOCX 在 `engine=paddle` 时回退 Tika。

---

## 智能问答助手

入口：`POST /api/chat`、`POST /api/chat_stream`（SSE，5 分钟）。DeepSeek 或通义千问，温度比一键分析高。流式只推模型文本，不推工具中间结果。

每轮：解析会话（空则新建）→ 有附件则抽文本拼进问题 → 取窗口历史（默认 6 对）和滚动摘要 → 用**用户原话**召回经验 → 模型自己调工具 → 这一轮写入 MySQL。回答返回后再异步做经验闭环、滚动摘要、情景归档。闲聊不沉淀经验。删会话只清消息、摘要和附件，**不清** Milvus 里的经验和情景记忆。

工具：时间、知识库、Prometheus、跨会话回忆、记忆读写、`listSkills` / `loadSkill`，外加 CLS MCP。提示词里仍有「查天气」，代码里没有这个工具。

### 失败兜底

| 失败 | 怎么兜 |
|------|--------|
| 经验召回失败 | 空块注入，问答照常进行 |
| 滚动摘要读失败 | 当轮不带摘要，窗口内历史仍在 |
| Agent 抛错 | 非流式返回错误文案；流式推 `type=error` |
| 流式超时 | SSE 5 分钟后结束 |
| 回答已返回后的经验闭环 / 摘要 / 情景归档失败 | 只打日志，**不撤回已给出的回答** |
| Skill 启动扫描失败 | 不影响应用启动；运行时 `loadSkill` 再按名字取手册 |

---

## 上下文与记忆

| 层 | 存什么 | 怎么进下一轮 |
|----|--------|--------------|
| 工作记忆 | 最近窗口内的原文 | 每轮做成原生多轮消息 |
| 滚动摘要 | 滑出窗口的早期对话压缩 | 写入 MySQL，注入系统提示词 |
| 情景记忆 | 每一轮问答的向量 | 用户说「上次 / 之前」时调 `searchPastConversations` |
| 经验 | 排障结论的向量 + 生命周期元数据 | 每轮自动召回注入；也可 `searchMemory` / `saveMemory` |

滚动摘要、经验提炼和合并**固定走 DeepSeek**，不跟随前端切换的对话模型。摘要失败本轮跳过，未覆盖消息留待下一轮。情景记忆每轮都归档（不限于排障），空问题则跳过。

经验沉淀：

```
非排障对话 → 跳过
LLM 抽不出根因或置信过低 → 丢弃
与已有条目同一故障模式且已闭环 → 合并更新，不新增重复
否则写入 Milvus experience + MySQL experience_meta
  人工标记或「已解决且指标对得上」→ 强经验
  其余 → 弱经验（仍入向量库，提示词标待验证；旧表 experience_temp 只清历史遗留）
```

召回：按症状向量粗筛 → 按环境（mysql / redis / 服务名等）过滤 → 按相似、可信度、新近程度排序后注入。采纳某条时回答须写 `采用经验: <expId>`。

聊天闭环和一键分析不同：聊天只给**真正被引用**的条目记使用次数；仅当本轮提炼合并进了已召回条目时才上调置信度；**聊天不做负反馈**。一键分析见下一节。用户说「记住这个」走 `saveMemory`，直接按强经验保存。`POST /api/experience/mark` 只用当前窗口历史，不是全量会话。

长期不用会衰减，过低则归档删除。基础设施故障（网络 / LLM 挂了）不改经验分。

### 失败兜底

| 失败 | 怎么兜 |
|------|--------|
| 非排障、无根因、JSON 解析失败、置信过低 | 本轮不写入经验 |
| 高相似但故障模式不同 | 不合并，按新模式另存或丢弃 |
| 合并 LLM 失败或输出为空 | **保留旧经验**，不删向量 |
| 经验召回 / 情景检索失败 | 返回空，主对话继续 |
| 滚动摘要生成为空或 LLM 失败 | 跳过本轮滚动，未覆盖消息下次再试 |
| 情景归档失败 | 本轮不进 `episodic`，不影响回答 |
| 网络或 LLM 挂了 | 不给经验打负分 |

---

## 一键告警分析

入口：`POST /api/ai_ops`（SSE，超时 10 分钟）。不需要用户输入问题。请求体只用可选的模型 `provider`。触发方式仍是手动一键（前端按钮 / 评测脚本），不接 Alertmanager webhook。

编排是显式七节点流水线（详见 `docs/一键排障编排方案.md`），节点之间用类型化白板通信，**不用 A2A**：

```
段 A（/api/ai_ops）
  ① intake 拉 Prometheus 快照、拓扑归并、预召回经验
  → ② rca Planner / Executor / Supervisor 规划→执行→再规划（Prometheus / CLS / 知识库）写出《告警分析报告》+ JSON 结论
  → ③ plan 从 playbook 目录选处置并 SSH 前置检查
  → ④ risk 按 L0/L1/L2 分级
      L0/L1 → ⑤ execute → ⑥ verify → ⑦ distill
      无计划 / 熔断 → ⑦ distill
      L2 → 结束，incident 落库为 PENDING_APPROVAL

段 B（POST /api/incidents/{id}/approve）
  从 state_json 读回白板 → ⑤ execute → ⑥ verify → ⑦ distill
```

LLM 只出现在 ② 根因分析和 ⑦ 经验提炼；其余节点是确定性 Java。② 的引擎由 `aiops.orchestration.rca.engine` 决定：`plan-execute`（默认，Supervisor 内层图对外层七节点图是黑盒，不共用白板）或 `react`（单个 ReactAgent）。两种引擎产物一致：报告正文 + 尾部 ```json 结论；结论缺失时再用一次无工具 LLM 调用从报告抽取，仍失败则只按告警名 / 关键词兜底选 playbook 并按 L2 处理。RCA 有墙钟超时（默认 480s）与 SSE 心跳。自愈命令只能来自 `aiops.orchestration.playbooks`，不能自由生成。`remediation.enabled=false` 时全流程 dry-run，不连 SSH。

SSE 仍推 `content / done / error`（评测脚本不用改）。额外类型：`stage`（节点进度）、`approval`（待审批卡）、`incident`（终态）。L2 在前端渲染审批卡，批准后走段 B。

### 失败兜底

| 失败 | 怎么兜 |
|------|--------|
| Prometheus 连接重置（Windows NAT 常见） | 最多 8 次指数退避重试；不用 gzip 连接池，失败则工具返回错误 JSON，**不回落到本机经验库虚机** |
| 拉告警失败或没有 firing 名 | 用兜底词做经验召回；RCA 改查 CLS 是否有 Too many connections 等未覆盖故障 |
| 工具连不上 Prometheus | 不得写成「当前无告警」；有 firing 时以启动时快照继续排查 |
| CLS 查到 0 条 | 不等于业务无故障，禁止编造日志 |
| RCA 没附上 JSON 结论 | 仍输出 Markdown 报告，不自愈 |
| 前置检查失败 / 熔断 | `NO_ACTION`，只出报告 |
| 报告对不上当前 firing 告警名 | 对已采纳经验负反馈，**不再沉淀** |
| 编排抛错 | incident 记 `FAILED`，SSE `error` |

Prometheus 连不上不等于没有告警。一键分析不挂情景记忆和 `saveMemory`。

两套评测分开，不要混分。造故障只 SSH 远端 tjxt，不要停本机经验库 MySQL。未开 CLS 禁止注入故障。

- **第一次诊断**（告警核实 → CLS → RAG → 报告）：`python eval/run_oncall_pipeline_eval.py fw-01`
- **经验飞轮**（沉淀 → 召回 → 有用）：`python eval/run_flywheel_eval_single.py fw-01`

---

## CLS 日志查询 Skill

手册全文不塞进每轮系统提示词，按需 `loadSkill`（name 为 `cls-log-query`）。不确定有哪些 skill 时先 `listSkills`。加载时会把运行时配置里的主题 / TopicId 对照表附在手册后面。

文件：`src/main/resources/skills/cls-log-query/SKILL.md`。

必须顺序（禁止跳步、禁止编造日志）：

1. `ConvertTimeStringToTimestamp`：得到 SearchLog 的 From / To，默认近 15 分钟
2. `GetTopicInfoByName`：可选，未知 TopicId 时用；Region 必须是 `ap-chengdu`
3. **`TextToSearchLogQuery`**：自然语言转 CQL。`SearchLog` 前必须先走这一步
4. `SearchLog`：用上一步的 CQL；Limit 默认 10，不要超过 20
5. `DescribeLogContext`：需要对某条 ERROR 看前后文时再调

本环境几乎只有全文索引。禁止 `level:ERROR`，也禁止 `UnknownHostException:` 这种把异常类当字段；改用全文 `ERROR` 或 `"UnknownHostException"`。同一条非法 CQL 失败后禁止原样重试；同一工具连续失败三次应停手说明。

主题形如 `tjxt-dev-{service}-log-ap-chengdu`。入口流量优先查 gateway，登录 / token 查 auth，再按告警 label 落到对应服务。日志高度重复时不要盲目加大 Limit，先看量级再抽 1 条做代表。

Windows 下 MCP 不要把启动横幅打进 stdout。`application-example.yml` 走 `scripts/cls-mcp-stdio-wrapper.cjs`，只转发合法 JSON-RPC；`mcp-servers.json.example` 仍是直接 `npx`，本机容易握手失败。

问答和一键分析的系统提示词里只有短规则；完整顺序、CQL 禁令和 TopicId 表以 `loadSkill` 为准。知识库里的 CLS 指南若仍写 `level:ERROR`，以本 Skill 为准。

### 失败兜底

| 失败 | 怎么兜 |
|------|--------|
| `SearchLog` 报 `not indexed` / `SyntaxError` / `field: xxx` | 把报错里的字段改成带引号的全文词，或重新 `TextToSearchLogQuery`；**禁止同一条带冒号 CQL 原样重试** |
| 同一工具连续失败 3 次或一直空 | 停该方向，如实说明 |
| 近窗口 0 条 | 正常现象，可扩到近 1 小时再查一次；仍空则如实反馈，不要编造 |
| 10 条日志几乎相同 | 不要把 Limit 加到 50/200；先看量级，抽 1 条做代表，必要时再看上下文 |
| Windows MCP 握手被启动横幅打坏 | 用 `cls-mcp-stdio-wrapper.cjs` 只转发合法 JSON-RPC |
| Skill 文件解析失败 | 启动时跳过该手册，不影响主流程 |

---

## 配置与目录

```bash
cp src/main/resources/application-example.yml src/main/resources/application.yml
cp src/main/resources/mcp-servers.json.example src/main/resources/mcp-servers.json
```

Agent 本机 `127.0.0.1:9900`。经验库 / Milvus 在本机虚机。Prometheus 对准远端 tjxt（先开 SSH 隧道），不要用本机 `192.168.150.101:9090`。`spring.ai.model.chat: none`，对话模型由 `ChatModelFactory` 按请求创建。

```
controller/          对外接口（含 /api/ai_ops、/api/incidents）
aiops/               一键排障七节点编排、白板、SSH playbook
service/             对话、记忆、经验、向量、文档解析
agent/tool/          本地工具（含 loadSkill）
resources/skills/    按需加载的手册
aiops-docs/          知识库唯一来源
eval/                飞轮评测、一键排障诊断评测
scripts/             CLS MCP stdio 包装
docs/                分功能更新日志与编排方案
```
