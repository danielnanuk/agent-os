# agent-runtime-common

跨模块共享的类型定义。这个模块**不依赖任何基础设施**（没有数据库、没有 Temporal SDK、没有 Spring AI）—— 只有纯 Java `record`、枚举和几个小工具类，任何模块都可以放心依赖它而不会拖进一堆传递依赖。

## 目录结构

```
common/agent/       LlmProvider, AgentRunStatus, MessageRole, LlmModelConfig
common/activity/    Temporal Activity 请求/响应 DTO（record）
common/workflow/    Temporal Workflow 输入/输出 DTO（record）
common/event/       ProgressEvent, ProgressEventType, ProgressEventPublisher
common/tenant/      TenantContext
common/exception/   AgentRuntimeException, NotFoundException
```

## 为什么这些类型要单独放一个模块

- **`agent-runtime-temporal`** 里的 Activity/Workflow 方法签名，参数和返回值必须是能被 Temporal SDK 序列化的稳定 POJO —— 不能直接用 Spring AI 的 `Message`/`ChatResponse` 这类内部对象（那些类型的字段结构会随 Spring AI 版本变化，一旦变了会直接破坏 Temporal 的历史重放）。`ChatMessageDto`、`ToolCallDto`、`LlmResponse` 这些类型就是为了在 Temporal 边界上提供一层稳定的契约。
- **`ProgressEventPublisher`** 只在这里定义成一个接口，真正的 Redis 实现放在 `agent-runtime-api`（依赖倒置）—— 这样 `agent-runtime-temporal` 模块本身完全不需要知道 Redis 的存在，它只依赖这个抽象。
- **`TenantContext`** 是一个 `ThreadLocal<String>`，**只应该在发起 HTTP 请求的那个线程里使用**（由 `agent-runtime-api` 的 `TenantContextFilter` 填充）。Temporal 的 Workflow/Activity 线程是池化、跨租户复用的，所以从 Controller 往下的每一层都应该把 `tenantId` 当作显式方法参数传递，绝不应该在 Temporal 代码里重新去读这个 ThreadLocal。

## 关键类型

- **`LlmModelConfig`**（record：`provider`、`baseUrl`、`apiKeyEnv`、`modelName`）—— 描述"怎么连到一个具体的 LLM 端点"的完整快照，是 `agent-runtime-llm` 模块里 `ChatModelResolver` 用来做缓存 key 的对象。
- **`ChatMessageDto`** —— 一条对话消息的纯 POJO 表示，带 `toolCalls` 字段（只有请求了工具调用的 ASSISTANT 消息才会有值），必须在下一次 LLM 调用时原样回传，让模型看清楚它当初发起的工具调用请求对应的是哪次 TOOL 响应。
- **`AgentRunInput`** —— Workflow 需要的全部信息的一次性快照（`agent-runtime-api` 在启动 Workflow 之前查库解析好），包含 `priorHistory`（多轮对话的历史，为空表示新会话）。
- **`ProgressEventType`** —— 枚举了 SSE/语音场景消费的所有进度事件类型：`RUN_STARTED`、`LLM_DELTA`（逐 token 增量）、`LLM_THOUGHT`、`TOOL_CALL_STARTED`、`TOOL_CALL_COMPLETED`、`RUN_COMPLETED`、`RUN_FAILED`、`WAITING_FOR_APPROVAL`。
