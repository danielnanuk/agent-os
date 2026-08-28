# Agent Runtime

一个基于 **Spring Boot 4 + Spring AI 2** 和 **Temporal** 构建的通用多租户多智能体编排平台，数据层用 **PostgreSQL**（会话、Agent 注册表、pgvector）和 **Redis**（SSE 流式桥接、缓存/会话态、分布式锁）。另有一个独立的 **Python `voice-worker`** 服务（LiveKit Agents），在同一个后端之上提供带对口型数字人头像的实时语音通话能力。

这不是一个写死的单一聊天机器人：Agent 是注册在数据库里的数据（`agent_definitions`），每次运行都是一个可持久化的 Temporal workflow，每一次 LLM 调用 / 工具调用 / 持久化写入都是独立、可单独重试的 Temporal Activity —— 在 Temporal Web UI 里逐步可见。

## 架构总览

```
网页聊天 UI (static/index.html)            语音通话 (浏览器麦克风 + LiveKit)
  │  POST /api/v1/agent-runs                  │  POST /api/v1/voice-sessions
  │  GET  /api/v1/agent-runs/{id}/stream      │  (LiveKit 房间 token)
  ▼                                           ▼
agent-runtime-api  (Spring Boot：REST + SSE + Temporal Worker + MCP Server)
  │  启动 / 信号 / 查询                              LiveKit Cloud 房间
  ▼                                                        │ 自动分配
Temporal Server ────────────────────────────       voice-worker (Python)
  │  AgentRunWorkflow (ReAct 循环)                   ElevenLabs STT/TTS
  │    ├─ LlmActivities.callChatModel   ◄──HTTP+SSE── BackendLLM 桥接
  │    ├─ ToolActivities.invokeTool                  Spatius 头像 (视频)
  │    ├─ PersistenceActivities.*
  │    └─ StreamingActivities.publishEvent → Redis pub/sub → SSE
  ▼
PostgreSQL (+ pgvector)          Redis (pub/sub 桥接)
```

为什么用 Temporal 驱动循环而不用 Spring AI 内置的工具调用：`ChatModel.stream()` 是被直接调用的（绕开了 `ChatClient` 的自动工具执行），这样**每一次 LLM 调用、每一次工具调用都是独立的 Activity**，各自有独立的重试策略，且在 Temporal 的事件历史里完全可见。Workflow 代码本身保持确定性、不做任何 I/O；所有 I/O —— 包括往 Redis 推送进度 —— 都发生在 Activity 里。

## 模块结构（Maven 多模块，Java 21）

| 模块 | 依赖 | 内容 |
|---|---|---|
| `agent-runtime-common` | — | 跨模块 DTO（record）、枚举（`LlmProvider`、`AgentRunStatus`、`MessageRole`）、`LlmModelConfig`、`TenantContext`、`ProgressEventPublisher` 接口、异常类 |
| `agent-runtime-persistence` | common | JPA 实体（`Tenant`、`AgentDefinition`、`LlmModel`、`AgentRun`、`Conversation`、`ConversationMessage`）、Spring Data 仓储、Flyway 迁移脚本 |
| `agent-runtime-llm` | common | `ChatModelResolver`（按 `llm_models` 表里的每一行构建/缓存对应的 `ChatModel` 客户端）、本地 `@Tool`（`TimeTools`、`WebSearchTools`）、`ToolCallbackRegistry` |
| `agent-runtime-mcp` | common | MCP **client** 角色 —— `McpToolCallbackRegistry` 解析外部 MCP Server 暴露的工具 |
| `agent-runtime-temporal` | common, persistence, llm, mcp | Workflow/Activity 接口与实现、`WorkflowIdFactory`、`SearchAttributeKeys`、`TemporalQueues`（本身不是可独立运行的应用） |
| `agent-runtime-api` | temporal（间接依赖全部） | 唯一可运行的 Spring Boot 应用：REST 控制器、SSE 流式推送、Redis pub/sub 桥接、租户上下文过滤器、MCP **server** 角色、语音会话引导（`VoiceSessionController`）、静态网页聊天+语音通话 UI、Temporal Worker（本地开发时与 API 同进程） |

内部依赖方向是一条直线（`common → persistence → llm → mcp → temporal → api`）—— 没有循环依赖。MCP 的 server 角色工具放在 `agent-runtime-api` 而不是 `agent-runtime-mcp`，专门是为了避免 `mcp → temporal → mcp` 的模块循环（server 角色需要 `AgentRunService`，而它又需要 Temporal 的 `WorkflowClient`）。

`voice-worker/`（Python）完全在 Maven reactor 之外 —— 详见下文[实时语音](#实时语音livekit--elevenlabs--spatius)章节。

每个 Maven 模块目录下都有自己的 `README.md`，写了该模块更细的职责和关键文件，这份根 README 只讲整体架构和跨模块的设计决策。

## 核心设计决策

- **租户隔离是显式参数，绝不用超出请求线程范围的 `ThreadLocal`。** `TenantContext`（ThreadLocal）由 `TenantContextFilter` 填充，通过 Controller 方法参数上的 `@TenantId` 读取一次。从这里往下 —— Service、Temporal 的 `AgentRunInput`、每个 Activity 的请求 DTO、每个仓储方法 —— `tenantId` 全部作为显式参数传递。Temporal 的 Workflow/Activity 线程是池化且跨租户复用的，在那一层用 ThreadLocal 就是一个等着发生的跨租户数据泄露。
- **LLM 模型由数据库驱动，不是写死的 Spring Bean。** 一行 `llm_models` 记录（`provider`、`base_url`、`api_key_env`、`model_name`）就是一个可复用、有名字的模型端点配置；`agent_definitions.llm_model_id` 指向其中一行。`ChatModelResolver` 直接用各供应商自己的 Java SDK builder，按每个不同的 `LlmModelConfig` 构建（并缓存）一个 `ChatModel` 客户端 —— 这使得两个同样配置成 `provider=OPENAI` 的 Agent 可以同时指向完全不同的 OpenAI 兼容端点/密钥（比如一个用 DeepSeek，一个用 GLM），新增或重新绑定模型是纯数据变更，**不需要重启应用**。
- **Workflow ID** = `tenant-{tenantId}-agentrun-{agentRunId}`（见 `WorkflowIdFactory`），再加上自定义 Temporal **Search Attributes**：`TenantId` 和 `AgentKey`（Keyword 类型），所以可以直接用 `temporal workflow list --query "TenantId='acme-corp'"` 或在 Web UI 里按租户查询运行记录。
- **流式推送走 Activity → Redis → SSE，不是 Workflow 直连 Redis**，而且是真正的逐 token 流式。`LlmActivitiesImpl` 调用 `chatModel.stream(...)`，每收到一个文本增量就立刻发布一个 `LLM_DELTA` 进度事件（通过 Spring AI 的 `MessageAggregator` 实现，它在流结束时仍然会给出一个完整合并好的 `ChatResponse`——包括重新拼装好的 tool call——所以 Workflow 里的 ReAct 循环逻辑完全不用改）。`StreamingActivities`（Activity，跑在 Worker JVM 上）发布到按 run 区分的 Redis 频道（`agent-events:{tenantId}:{runId}`），`agent-runtime-api` 里的 `RedisSubscriptionBridge` 把它转发给任意 SSE 连接 —— 哪怕这个连接落在跟跑 Worker 不同的 API 实例上。语音通话也是复用这套同样的 `LLM_DELTA` 事件转成 TTS 音频的。
- **Agent 定义是数据，不是代码。** `agent_definitions` 表的每一行带着 `llm_model_id`、`system_prompt`、`max_iterations`、`tool_names` —— 注册新 Agent 是写 SQL/调 API，不是写新的 Java 类。`tenant_id IS NULL` 的行是全局共享的演示 Agent（查询语句里的兜底逻辑：`WHERE (tenant_id = :t OR tenant_id IS NULL) AND agent_key = :key`）。
- **多轮记忆靠 `conversations` 表，不是靠单次 run 的隔离状态。** 每次 `POST /agent-runs` 调用仍然是一次独立的 Temporal Workflow 运行（一次完整的 ReAct 循环），但如果带上前一次响应返回的 `conversationId`，新一轮 run 的历史会被预填充进该会话此前的 USER 输入和最终 ASSISTANT 回答（工具调用过程中的中间态 assistant 消息、原始 TOOL 结果消息故意不带进上下文 —— 这些内容模型已经在当时那一轮里用过了）。
- **向量表的租户隔离靠 metadata，不是原生列。** `pgvector` 的每一行把 `tenant_id` 存进 `metadata` JSONB 字段，查询时用 Spring AI 的 `SearchRequest.filterExpression(...)` 过滤 —— 完全复用现成的 `VectorStore` 抽象、零自定义代码，代价是这个过滤走不了索引加速（在目前的数据规模下可以接受）。
- **人工介入的钩子已经结构性存在。** `AgentRunWorkflow` 除了 `getProgress`（query）之外，还暴露了 `submitApproval`（signal）和 `cancel`（signal）—— 即使当前的演示 Agent 用不上，`WAITING_FOR_APPROVAL` 这个暂停/恢复点也已经就位。
- **鉴权在这个阶段是明确排除在外的。** `TenantContextFilter` 直接信任 `X-Tenant-Id` header 的值（只对 `/api/**` 生效，不影响静态资源）—— 这个过滤器就是未来接鉴权的地方，到时候应该校验真实的身份声明，而不是信任裸 header。

## 数据模型（Flyway，`agent-runtime-persistence/src/main/resources/db/migration/`）

| 迁移 | 表 / 改动 | 说明 |
|---|---|---|
| `V1` | — | `CREATE EXTENSION vector` |
| `V2` | `tenants` | `tenant_key`（唯一），首次出现某个 `X-Tenant-Id` 时自动创建 |
| `V3` | `agent_definitions` | Agent 注册表：`tenant_id` 可为空（NULL=全局）、`system_prompt`、`max_iterations`、`tool_names`（jsonb） |
| `V4` | `agent_runs` | `id` = agentRunId、`workflow_id`（唯一）、`status`、`input_payload`/`result_payload`（jsonb）、`error_message` |
| `V5` | `conversation_messages` | `role`、`content`、`tool_name`、`tool_call_id`、`sequence_number` —— 完整 ReAct 记录 |
| `V6` | `vector_store` | 手写、跟 Spring AI 默认 `PgVectorStore` 的 DDL 保持一致（HNSW + cosine），这样它也归 Flyway 统一管理，而不是走 Spring AI 自己的 `initialize-schema` |
| `V7` | — | 种下一个全局演示 Agent：`general-assistant` |
| `V8` | — | 给演示 Agent 的 `tool_names` 加上 `web_search` |
| `V9` | `llm_models` | 把 `agent_definitions` 上原来的 `llm_provider`/`llm_model` 抽成独立、可复用的表（`provider`、`base_url`、`api_key_env`、`model_name`）；`agent_definitions.llm_model_id` 引用它 |
| `V10` | `conversations` | 把多次 `agent_runs`（每轮一次）归到同一个持续对话下；`agent_runs.conversation_id` 引用它 |

## 技术栈

- **Java 21**，Maven 多模块，跨模块 DTO 用 `record`（不用 Lombok）
- **Spring Boot 4.1.1** / **Spring AI 2.0.1**（新版 `spring-ai-starter-*` 命名规范）
- **Temporal Java SDK 1.37.0** + `temporal-spring-boot-starter`（Worker 在 `agent-runtime-api` 里自动注册）
- **PostgreSQL 16**（`pgvector/pgvector:pg16` 镜像）—— 应用数据；Temporal 自己的内部 schema 用**单独一个** `postgres:16-alpine` 实例
- **Redis 7** —— pub/sub 流式桥接
- **MCP**（Model Context Protocol）—— 双重角色：client（消费外部工具服务）和 server（`invoke_agent`/`get_agent_run_result` 工具，把本平台的能力暴露出去）
- **Flyway** —— 所有 schema（包括向量表）统一走一套迁移工具管理
- **Testcontainers** —— 持久化层集成测试
- **Tavily** —— 支撑 `web_search` 工具
- **LiveKit + ElevenLabs + Spatius**（Python `voice-worker`）—— 带头像的实时语音通话

在动这份代码之前值得知道的几个 Spring Boot 4 / Spring AI 2 的坑：
- Spring Boot 4 的自动配置被模块化拆分了，单独引入 `flyway-core` 已经不会触发 `FlywayAutoConfiguration` —— 本项目用的是 `spring-boot-starter-flyway`（见 `agent-runtime-persistence/pom.xml`）。
- Spring Boot 4 默认用 Jackson 3（`tools.jackson.*`，不可变的 `JsonMapper`），**不会**自动配置 `com.fasterxml.jackson.databind.ObjectMapper` 这个 Bean。这份代码仍然显式使用 Jackson 2；`JacksonConfig`（`agent-runtime-api`）手动接了这个 Bean。
- 每个具体的 Spring AI `ChatModel`（`OpenAiChatModel`/`AnthropicChatModel`/`OllamaChatModel`）内部都会把 `Prompt.getOptions()` 强转成自己**专属**的 `ChatOptions` 子类 —— 用通用的 `ToolCallingChatOptions.builder()` 构建的话，调用时会直接抛 `ClassCastException`。`LlmActivitiesImpl.buildOptions(...)` 按 provider 分支，用各自的 builder。
- 动态构建 `ChatModel`（而不是让 Spring 自动配置一个）需要**同时**提供同步和异步两个供应商客户端（`OpenAiChatModel.builder().openAiClient(...).openAiClientAsync(...)`）—— 如果漏了异步客户端，builder 会尝试从环境变量兜底推导一个，结果哪怕同步客户端配置完整，也会直接抛 `"At least one credential source must be specified"`。详见 `ChatModelResolver`。
- `spring.ai.model.embedding: openai` 是显式设置的，因为 `PgVectorStoreAutoConfiguration` 要求**恰好一个** `EmbeddingModel` Bean。

## 本地开发环境

`docker-compose.yml` 会拉起：

| 服务 | 镜像 | 端口 | 用途 |
|---|---|---|---|
| `postgres` | `pgvector/pgvector:pg16` | `5432` | 应用数据库 |
| `temporal-postgresql` | `postgres:16-alpine` | `5433` | Temporal 自己的 schema（跟应用数据库解耦） |
| `temporal` | `temporalio/auto-setup:1.29.1` | `7233` | Temporal Server（gRPC/SDK） |
| `temporal-setup-search-attributes` | `temporalio/admin-tools:1.29.1-...` | — | 一次性容器：注册 `TenantId`/`AgentKey` search attribute（可安全重复执行） |
| `temporal-ui` | `temporalio/ui:2.34.0` | `8088` | Temporal Web UI（避开应用的 8080 端口） |
| `redis` | `redis:7-alpine` | `6380`（宿主机）→`6379` | pub/sub 流式桥接（因本地端口冲突改映射到 6380） |
| `ollama` | `ollama/ollama` | `11434` | 可选，走 `local-llm` profile |

```bash
# 拉起核心基础设施
docker compose up -d postgres temporal-postgresql temporal temporal-setup-search-attributes temporal-ui redis

# 可选：本地 LLM
docker compose --profile local-llm up -d ollama
```

## 配置（`agent-runtime-api/src/main/resources/application.yml`）

| 环境变量 | 默认值 | 用途 |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | `localhost` / `5432` / `agent_runtime` / `agent_runtime` / `agent_runtime` | 应用 Postgres |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6380` | Redis（对应 compose 里改过的端口映射） |
| `TEMPORAL_TARGET` | `127.0.0.1:7233` | Temporal Server gRPC 地址 |
| `OPENAI_API_KEY` | — | 仅用于 Embedding（`text-embedding-3-small`，平台级设置，跟任何 Agent 用哪个 chat 模型无关） |
| `TAVILY_API_KEY` | — | 支撑 `web_search` 工具；不设的话工具照样能注册，只是调用时会失败 |
| `LIVEKIT_API_KEY` / `LIVEKIT_API_SECRET` / `LIVEKIT_URL` | — | 只有实时语音功能需要；`VoiceSessionController` 用它们签发房间 token |

**每个 Agent 具体用哪个 chat 模型，完全不在这里配置。** 每一行 `llm_models` 记录自带 `base_url` + `api_key_env`（一个环境变量的*名字*，比如 `OPENAI_CHAT_API_KEY` 或 `DEEPSEEK_API_KEY`——数据库里永远不存明文密钥）+ `model_name`；`ChatModelResolver` 在调用时通过 `System.getenv(...)` 读取这个环境变量。要把某个 Agent 切换到新的供应商：插入一行 `llm_models`，export 它引用的那个环境变量，更新 `agent_definitions.llm_model_id` —— 不用改代码，不用重启。

**永远不要把明文密钥直接贴进会被记录的聊天/终端历史里** —— 先在你自己的 shell 会话里 export 好，再从同一个 shell 里启动应用。

## 运行

```bash
mvn -pl agent-runtime-api -am spring-boot:run
```

启动时：Flyway 会跑完所有迁移（建好 `tenants`、`agent_definitions`、`llm_models`、`agent_runs`、`conversations`、`conversation_messages`、`vector_store`，并种下一行 `llm_models` 演示数据和指向它的 `general-assistant` Agent），Temporal Worker 会向任务队列 `agent-runtime-default` 注册。

打开 **http://localhost:8080** 即可看到内置的网页聊天 UI（如果 `voice-worker` 也在跑，还会有一个 🎙️ 语音通话按钮）。

## API

所有 `/api/**` 接口都要求带 `X-Tenant-Id`（缺失/为空 → `400`；租户首次出现时自动创建）。跨租户访问别人的 run 会返回 `404`，这是在仓储层通过显式的 `tenantId` 查询条件强制保证的。

```bash
# 提交一次任务（不带 conversationId 就是开启一个新会话）
curl -X POST http://localhost:8080/api/v1/agent-runs \
  -H "X-Tenant-Id: acme-corp" -H "Content-Type: application/json" \
  -d '{"agentKey":"general-assistant","input":"现在几点？"}'
# → 202 {"runId": "...", "conversationId": "...", "status": "PENDING"}

# 延续同一个会话（模型会记得之前几轮说过的内容）
curl -X POST http://localhost:8080/api/v1/agent-runs \
  -H "X-Tenant-Id: acme-corp" -H "Content-Type: application/json" \
  -d '{"agentKey":"general-assistant","input":"那东京呢？","conversationId":"<上面返回的 conversationId>"}'

# 查询状态/结果
curl -H "X-Tenant-Id: acme-corp" http://localhost:8080/api/v1/agent-runs/{runId}

# 流式查看进度（SSE）—— 先重放已持久化的历史，再推送实时事件
curl -N -H "X-Tenant-Id: acme-corp" http://localhost:8080/api/v1/agent-runs/{runId}/stream
```

SSE 事件顺序：`history`（重放历史消息）→ `status`（当前状态）→ 实时 `progress` 事件：`RUN_STARTED → LLM_DELTA（大量、逐 token）→ LLM_THOUGHT → TOOL_CALL_STARTED → TOOL_CALL_COMPLETED → LLM_DELTA... → RUN_COMPLETED`（或 `RUN_FAILED`）。

## MCP

- **Client 角色**（`agent-runtime-mcp`）：通过 `McpToolCallbackRegistry` 消费外部 MCP 工具服务；在 `application.yml` 的 `spring.ai.mcp.client` 下配置一个真实的 MCP Server 即可接入（文件里带了一个用官方参考 filesystem server 的注释掉的示例）。
- **Server 角色**（`agent-runtime-api`，`AgentInvocationTools`）：把本平台的 Agent 暴露成 MCP 工具 —— `invoke_agent(tenantId, agentKey, taskInput, conversationId?)` 和 `get_agent_run_result(tenantId, runId)`，让外部 MCP 客户端能通过跟 REST API 一样的 `AgentRunService` 入口驱动 Agent 运行。

## 实时语音（LiveKit + ElevenLabs + Spatius）

语音通话走一个独立的 Python 服务 `voice-worker/`，因为 `livekit-agents`（STT→LLM→TTS 编排框架）官方只提供 Python/Node.js SDK —— 没有 Java 版本。**推理和工具调用依然全部发生在 Java 后端**；这个 worker 纯粹是语音输入输出层。

```
浏览器麦克风（LiveKit JS SDK，vendored 在 static/vendor/livekit-client.min.js）
  │  WebRTC
  ▼
LiveKit 房间  ──自动分配──▶  voice-worker（Python，livekit-agents）
                                STT: ElevenLabs
                                LLM: BackendLLM  ──HTTP POST + SSE──▶ agent-runtime-api
                                TTS: ElevenLabs                        （跟文字聊天用的是
                                Avatar: Spatius（视频）                  同一套 /agent-runs API）
```

1. 浏览器点「🎙️ 语音通话」→ `POST /api/v1/voice-sessions {agentKey}` → `VoiceSessionController` 创建一个 `Conversation`，返回一个 LiveKit 房间 token（token 的参与者 metadata 里嵌了 `tenantId`/`agentKey`/`conversationId`）。
2. 浏览器直接拿这个 token 加入 LiveKit 房间；LiveKit 自动把 `voice-worker` 分配进这个房间。
3. `voice-worker` 从加入者的参与者 metadata 里读出 `tenantId`/`agentKey`/`conversationId`，然后对每一句用户话语：ElevenLabs STT 转写 → `backend_llm.py` 里的 `BackendLLM`（一个自定义的 `livekit.agents.llm.LLM` 实现）把它 `POST` 到 `/api/v1/agent-runs` 并读取 SSE 流，把每个 `LLM_DELTA` 事件实时翻译成 LiveKit 的 `ChatChunk` —— 这样 ElevenLabs TTS 能在完整答案生成完之前就开始念 —— → Spatius 把它渲染成对口型的头像视频轨道。
4. 同一个 `conversationId` 会跟浏览器的文字聊天共用，所以一次语音通话和打字消息可以延续同一个多轮对话。

详见 `voice-worker/README.md`：环境搭建、必需的环境变量（`LIVEKIT_*`、`ELEVEN_API_KEY`、`SPATIUS_*`），以及一个记录了已知头像问题的排查章节（见下文）。

**已知问题（截至本文写作时）：Spatius 头像视频画面是纯黑的。** 通过浏览器端 `RTCPeerConnection.getStats()` 确认过：音频完全正常，但视频的 `inbound-rtp` 报告显示 `framesDecoded: 0`，且 `framesDropped` 等于 `framesReceived` —— 每个视频包都收到了，但没有一帧被成功解码。这**不是**浏览器自动播放拦截问题，也不是这个仓库前端代码能修的 —— 视频码流在到达浏览器之前就已经损坏了。把 `SPATIUS_REGION` 固定成 `us-west`（而不是 `auto`）并没有解决问题。这个需要带着上面这些 WebRTC 统计数据去找 Spatius 技术支持。**语音输入输出这条链路（STT → 后端推理 → TTS）跟这个问题完全无关，是正常工作的** —— 已经用真人说话、包含真实工具调用的场景端到端验证过。

客户端（`@livekit/krisp-noise-filter`）或服务端（`livekit-plugins-noise-cancellation`，Krisp BVC）的降噪方案调研过，但特意没有接进来 —— Krisp BVC 依赖 LiveKit Cloud，且会产生额外计费（2026 年 5 月起生效）；等实际用起来真的遇到背景噪音问题了再加，不提前引入。

## 网页聊天 UI（`agent-runtime-api/src/main/resources/static/index.html`）

一个单页纯 JS 聊天界面（没有构建步骤，不依赖框架），作为 Spring 静态资源直接提供：
- 把 `LLM_DELTA` token 实时流式渲染进聊天气泡（不是等全部生成完才显示）。
- 渲染一个精简的 markdown 子集（标题、粗体/斜体、代码、链接、列表）—— 输出会先转义，所以模型或 `web_search` 结果里引用的任何内容都不能注入标签。
- 工具调用结果（比如 `web_search`，内容可能很长）默认折叠在一个 `<details>` 里。
- 多轮对话：同一个页面会话内连续发送时复用 `conversationId`。
- 🎙️ 语音通话按钮：通过 `/api/v1/voice-sessions` 引导出一个 LiveKit 房间，用 vendored 的 `livekit-client` SDK 加入，把订阅到的 Spatius 头像音视频轨道渲染出来。处理了浏览器自动播放拦截检测（`room.canPlaybackAudio`/`canPlaybackVideo`），配了一个"点击开启播放"的恢复提示。

## 在 Temporal 里查看运行记录

Temporal Web UI：http://localhost:8088

```bash
temporal workflow list --address 127.0.0.1:7233 --query "TenantId='acme-corp'"
```

每次运行的事件历史里能单独看到每一次 `CallChatModel` / `InvokeTool` / `AppendMessage` / `UpdateRunStatus` / `PublishProgressEvent` Activity —— 包括重试和失败。

## 现状 / 已验证内容

已用真实基础设施和真实 LLM/工具调用端到端验证过：租户自动创建、Agent 定义 + 动态 LLM 模型解析（包括两个 Agent 同时指向不同的 OpenAI 兼容供应商/密钥、零重启切换）、带真实工具调用的 Temporal workflow 执行（`get_current_time`、经 Tavily 的 `web_search`）、逐 token 的 `LLM_DELTA` 流式推送一路到网页 UI、多轮对话记忆（验证过模型真的记得住之前几轮提到的事实）、租户隔离（400/404 场景），以及完整的实时语音链路（真人说话 → ElevenLabs STT → Java/Temporal 后端推理并真实调用工具 → ElevenLabs TTS）—— 既做过真实浏览器测试，也做过 WebRTC 统计数据级别的无头验证。

还没跑过 / 已知的缺口：Spatius 头像视频渲染（见上文"已知问题"—— 纯语音功能不受影响）、跨 JVM 实例的 SSE 转发测试、针对真实外部 MCP 参考服务器的客户端集成测试、精确的语音通话延迟测量（从用户说完话到听到第一句回复音频之间的时间）。
