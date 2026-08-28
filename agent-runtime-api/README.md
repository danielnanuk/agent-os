# agent-runtime-api

唯一可运行的 Spring Boot 应用：REST API、SSE 流式推送、语音会话引导、MCP server 角色、静态网页 UI、Temporal Worker（本地开发时与 API 同进程）。依赖 `agent-runtime-temporal`，间接依赖了其他所有模块。

## 目录结构

```
AgentRuntimeApplication.java     Spring Boot 入口
api/config/                      JacksonConfig, RedisConfig, WebConfig
api/web/                         Controller、请求/响应 DTO、租户上下文过滤器
api/service/                     AgentRunService, TenantService
api/streaming/                   Redis pub/sub ↔ SSE 桥接
api/mcp/                         MCP server 角色（AgentInvocationTools）
resources/application.yml        全局配置
resources/static/                网页聊天 + 语音通话 UI（纯静态资源，无构建步骤）
```

## 租户上下文的传播链路

1. `TenantContextFilter`（`OncePerRequestFilter`）—— 只对 `/api/**` 路径生效（静态资源不受影响，否则网页 UI 自己都加载不出来）。要求请求带 `X-Tenant-Id` header，缺失/为空直接 `400`；把值写进 `TenantContext`（ThreadLocal），`finally` 块里清理。
2. `TenantIdArgumentResolver` + 自定义 `@TenantId` 注解 —— Controller 方法显式声明 `@TenantId String tenantId` 参数，而不是自己去掏 ThreadLocal，这样依赖关系在方法签名上就是可见的。
3. 从 Controller 往下 —— Service、Temporal 的 `AgentRunInput`、各个 Activity 请求 DTO、Repository 方法 —— 全部显式传参 `tenantId`，**绝不**在深层重新读取 ThreadLocal。这是保证 Temporal Worker 线程池化、跨线程复用场景下租户隔离安全的关键（Workflow/Activity 线程不是每个请求都新建的，ThreadLocal 在那一层是不安全的）。

当前阶段**没有做鉴权** —— `TenantContextFilter` 直接信任 header 里的值。这个过滤器就是未来接入真实身份校验（比如解析 JWT 里的租户声明）的插入点，接入后下游代码完全不用改，因为已经全部是显式传参了。

## `AgentRunService`：REST 和 MCP 共用的唯一编排入口

`startRun(tenantId, agentKey, taskInput, conversationId)` 是提交一次任务的核心逻辑，`AgentRunController`（REST）和 `AgentInvocationTools`（MCP server 角色）都调用同一个方法 —— 编排逻辑只写一份，不会出现两条路径行为不一致的问题。

内部流程：
1. 解析 `AgentDefinition`，再解析它指向的 `LlmModel`，拼成 `LlmModelConfig`。
2. 处理 `conversationId`：为空则新建一个 `Conversation`；非空则校验属于同一租户，并把该会话下此前所有轮次的 `conversation_messages` 拉出来、过滤（只保留 USER 输入和非空的最终 ASSISTANT 回答，工具调用中间态和原始 TOOL 结果不带进去），转成 `priorHistory`。
3. 新建一行 `AgentRun`，用 `WorkflowIdFactory` 生成 Workflow ID，设置好 `TenantId`/`AgentKey` 两个 Search Attribute，启动 Temporal Workflow。
4. 返回 `StartRunResult(agentRunId, conversationId)` —— 调用方（网页前端或语音 worker）需要这个 `conversationId`（尤其是新建会话时）才能在下一轮延续同一个对话。

## SSE 流式推送：跨 JVM 实例也能用

- `RedisProgressEventPublisher` —— `StreamingActivities`（跑在 Temporal Worker 上）通过这个类把进度事件发到 Redis，频道按 `agent-events:{tenantId}:{runId}` 区分，避免全量广播。
- `SseSubscriptionManager` —— 只管理**本 JVM 实例**持有的 SSE 连接（`ConcurrentHashMap<runId, List<SseEmitter>>`）。检测到终态事件（`RUN_COMPLETED`/`RUN_FAILED`）时会主动关闭相关连接 —— `SseEmitter` 建立时超时设的是 0（永不超时），如果不在终态时主动关闭，连接会永远挂着不释放。
- `RedisSubscriptionBridge` —— 实现 `MessageListener`，SSE 客户端连接时动态订阅对应频道，把收到的消息转发给 `SseSubscriptionManager`；在 `onCompletion`/`onTimeout`/`onError` 回调里退订，避免监听器泄漏。

这套桥接的意义在于：Temporal Worker 实际执行任务的那个 JVM 实例，可以跟持有某个客户端 SSE 连接的 JVM 实例是**不同的两个进程** —— 只要都连着同一个 Redis，消息照样能送达。

`AgentRunController.stream(runId)` 连接建立时会先重放已持久化的 `conversation_messages`（`history` 事件）和当前 `agent_runs.status`（`status` 事件）作为"补课"，再切换成实时尾随模式 —— 如果 run 在连接时已经是终态，直接推完历史就关闭连接，不会傻等一个永远不会来的事件。

## 语音会话引导（`VoiceSessionController`）

`POST /api/v1/voice-sessions {agentKey}` → `201 {roomName, livekitUrl, livekitToken, conversationId}`。

内部逻辑：新建一个 `Conversation`（跟文字聊天走的是同一张表，所以语音通话和打字消息能延续同一个多轮对话），房间名用 `voice-{conversationId}`，用 LiveKit 的 Java Server SDK（`io.livekit:livekit-server`）签发一个 `AccessToken`，把 `tenantId`/`agentKey`/`conversationId` 编码进 token 的 **metadata**（JSON 字符串）—— `voice-worker`（Python 服务）加入房间后从参与者 metadata 里读出这三个值，不需要额外的房间元数据查询往返。

这个 Controller**只负责签发 token**，真正的语音输入输出（STT/LLM 桥接/TTS/头像渲染）完全在 `voice-worker/` 这个独立的 Python 服务里，详见仓库根目录 README 的"实时语音"章节和 `voice-worker/README.md`。

## MCP server 角色（`AgentInvocationTools`）

把本平台的 Agent 暴露成 MCP 工具：`invoke_agent(tenantId, agentKey, taskInput, conversationId?)`、`get_agent_run_result(tenantId, runId)`，底层调用的还是 `AgentRunService`，跟 REST 入口共用同一套编排逻辑。之所以放在 `agent-runtime-api` 而不是 `agent-runtime-mcp` 模块，见 `agent-runtime-mcp/README.md` 里关于模块循环依赖的说明。

## 网页 UI（`static/index.html` + `static/vendor/livekit-client.min.js`）

单页纯 JS（无构建步骤、不依赖任何前端框架），作为 Spring 静态资源直接提供：

- **流式渲染**：收到 `LLM_DELTA` 事件就往当前气泡里追加文字，不是等 `RUN_COMPLETED` 才整块显示。
- **Markdown 渲染**：一个手写的极简 markdown 子集渲染器（标题、粗体/斜体、行内代码、代码块、链接、列表），渲染前统一先转义原始文本 —— 因为 assistant 回复或 `web_search` 结果里可能包含来自网页的不可信内容，转义之后即使里面混进 `<script>` 之类的标签也只会显示成文字，不会被当成 HTML 执行。
- **工具结果折叠**：工具调用返回的内容（比如 `web_search`，经常很长）用原生 `<details>/<summary>` 默认收起，不占屏幕空间。
- **多轮对话**：页面内维护一个 `currentConversationId`，连续发送时自动带上，真正体验到"模型记得住之前说过的话"。
- **语音通话**：🎙️ 按钮调 `/api/v1/voice-sessions` 拿到房间信息，用 vendored 的 `livekit-client` SDK（`static/vendor/livekit-client.min.js`，从 npm 直接下载放到本地，不依赖运行时 CDN）加入房间，把订阅到的 Spatius 头像音视频轨道渲染出来。处理了浏览器自动播放拦截检测（`room.canPlaybackAudio`/`canPlaybackVideo` + `AudioPlaybackStatusChanged`/`VideoPlaybackStatusChanged` 事件），弹出"点击开启播放"的恢复按钮，调 `room.startAudio()`/`room.startVideo()`。

## 本地 Spring Boot 4 相关的坑

- `JacksonConfig` 手动注册了一个 `com.fasterxml.jackson.databind.ObjectMapper` Bean —— Spring Boot 4 默认只自动配置 Jackson 3 的 `JsonMapper`，这份代码里到处用的还是 Jackson 2（`ObjectMapper.writeValueAsString`/`readValue`），所以需要手动接上。
- `RedisConfig` 配了 `RedisMessageListenerContainer` Bean（`RedisSubscriptionBridge` 订阅 Redis 频道要用到）；`WebConfig` 注册了 `TenantIdArgumentResolver`，让 Controller 方法上的 `@TenantId` 参数生效。
