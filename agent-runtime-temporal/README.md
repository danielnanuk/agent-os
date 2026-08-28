# agent-runtime-temporal

整个平台的编排核心：把 ReAct 循环实现成一个 Temporal Workflow，每一次 LLM 调用、每一次工具调用、每一次持久化写入都是独立的 Temporal Activity。这个模块本身**不是**一个可独立运行的应用（没有 `main` 方法），需要被 `agent-runtime-api` 引入并注册 Worker 才能真正跑起来。依赖 `agent-runtime-common`、`agent-runtime-persistence`、`agent-runtime-llm`、`agent-runtime-mcp`（间接依赖了全部下游模块）。

## 目录结构

```
temporal/                          WorkflowIdFactory, SearchAttributeKeys, TemporalQueues
temporal/workflow/                 AgentRunWorkflow 接口 + AgentRunWorkflowImpl
temporal/activity/                 4 个 Activity 接口
temporal/activity/impl/            对应的实现类 + CompositeToolResolver
```

## 为什么 ReAct 循环要用 Temporal Workflow 来跑，而不是 Spring AI 自带的工具调用循环

Spring AI 的 `ChatClient` 自带一个工具调用自动执行循环 —— 调一次就把"LLM 说要调工具 → 真的去调 → 把结果喂回去 → 再调 LLM"这整个过程在内存里跑完。这份代码**没有用**这个机制：`LlmActivitiesImpl` 直接调用底层 `ChatModel.stream(...)`，拿到的是带 `toolCalls` 的原始响应，真正"要不要继续循环、调哪个工具"这些决策全部由 `AgentRunWorkflowImpl.run()` 来做。

这么做是为了让**每一次 LLM 调用和每一次工具调用都是一个独立的 Temporal Activity**：
- 各自有独立的重试策略（LLM 调用超时约 60s、认证错误不重试；工具调用超时 30s；持久化写入超时 10s、重试 5 次，因为数据库抖动可以放心重试）。
- 在 Temporal Web UI 的事件历史里，能逐步看到 `CallChatModel`、`InvokeTool`、`AppendMessage` 这些每一步单独发生了什么、失败在哪一步、重试了几次 —— 而不是一个吞掉了中间过程的黑盒调用。
- 如果用 Spring AI 自己的循环 + Temporal 编排两套逻辑同时存在，会互相打架（两边都以为自己在管理"这一步该不该继续"）。

## `AgentRunWorkflow` 接口

```java
@WorkflowInterface
public interface AgentRunWorkflow {
    @WorkflowMethod
    AgentRunResult run(AgentRunInput input);

    @SignalMethod void submitApproval(ApprovalDecision decision); // 人工介入用
    @SignalMethod void cancel();
    @QueryMethod AgentRunProgress getProgress();
}
```

`submitApproval`/`cancel` 这两个 signal 目前的演示 Agent 用不上，但结构性地保留着 —— 为未来需要人工审批暂停点的 Agent 类型预留了钩子（`Workflow.await(...)` 阻塞点已经写在 `AgentRunWorkflowImpl` 里，只是当前循环不会走到那条分支）。

## ReAct 循环（`AgentRunWorkflowImpl.run`）

```
history 先用 input.priorHistory() 预填充（多轮对话场景；新会话则为空列表）
  ↓
updateRunStatus(RUNNING) → appendMessage(USER) + publish(RUN_STARTED)
  ↓
循环（最多 maxIterations 次）：
  callChatModel → appendMessage(ASSISTANT) + publish(LLM_THOUGHT)
    （callChatModel 内部会先逐 token publish(LLM_DELTA) 再返回完整结果，见下文）
  若有 tool call：逐个 invokeTool → appendMessage(TOOL) + publish(TOOL_CALL_*)，继续下一轮
  若没有 tool call：finalAnswer = 这次的回答，跳出循环
  ↓
saveRunResult + updateRunStatus(COMPLETED) + publish(RUN_COMPLETED)
```

失败处理：循环里任何一步抛出未捕获异常，会被顶层 `catch` 住，写 `updateRunStatus(FAILED, 错误信息)` + `publish(RUN_FAILED)`，再重新抛出一个不可重试的 `ApplicationFailure`。

## 四个 Activity 接口

| Activity | 职责 | 超时/重试 |
|---|---|---|
| `LlmActivities.callChatModel` | 调用 `ChatModelResolver` 解析出的 `ChatModel`，用 `chatModel.stream(...)` + Spring AI 的 `MessageAggregator` 实现真正的逐 token 流式：每收到一个文本增量就立刻发一个 `LLM_DELTA` 进度事件，流结束时 `MessageAggregator` 给出一个完整合并好的响应（包括重新拼装好的 tool call）作为 Activity 的返回值 | 约 60s 超时，重试 3 次（认证错误 `LLM_AUTH_ERROR` 不重试） |
| `ToolActivities.invokeTool` | 分发到本地 `@Tool` 或 MCP client 工具（`CompositeToolResolver` 统一解析） | 30s 超时，重试 2 次 |
| `PersistenceActivities.*` | `appendMessage`/`updateRunStatus`/`saveRunResult` 写 Postgres | 10s 超时，重试 5 次 |
| `StreamingActivities.publishProgressEvent` | 往 Redis 发一条进度事件，供 SSE/语音消费 | best-effort，重试 2 次，失败只记日志、不影响主流程（真相在 Postgres，流式推送只是体验优化） |

**为什么流式推送必须走 Activity，不能让 Workflow 直接连 Redis：** Workflow 代码必须是确定性的、不能做任何 I/O（Temporal 靠重放事件历史来恢复状态，Workflow 里的非确定性操作会破坏这个机制）。Redis 的 publish 属于 I/O，必须放进 Activity 里做。Signal 是外部→Workflow 方向（审批、取消），不适合用来做 Workflow→外部的进度上报，所以进度上报走的是 Activity。

## Workflow ID 与 Search Attribute

Workflow ID 格式是 `tenant-{tenantId}-agentrun-{agentRunId}`（见 `WorkflowIdFactory`），额外注册了两个自定义 Search Attribute（Keyword 类型）：`TenantId`、`AgentKey`，在 `agent-runtime-api` 启动 Workflow 时通过 `WorkflowOptions.setTypedSearchAttributes(...)` 设置，这样可以直接用 `temporal workflow list --query "TenantId='acme-corp'"` 按租户查询运行记录。

TaskQueue 目前是单队列 `agent-runtime-default`；未来如果要按 Agent 类型隔离资源，可以扩展成 `agent-runtime-{agentKey}` 这样的多队列方案。
