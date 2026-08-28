# agent-runtime-mcp

MCP（Model Context Protocol）**client** 角色：让本平台的 Agent 能调用外部 MCP 服务器暴露的工具。只依赖 `agent-runtime-common`。

## 目录结构

```
mcp/client/   McpToolCallbackRegistry
```

## 这个模块只有 client 角色

MCP 有两个方向：
- **Client**：本平台去消费别人暴露的工具（这个模块负责的部分）。
- **Server**：把本平台自己的 Agent 暴露成别人能调用的 MCP 工具。

Server 角色的代码（`AgentInvocationTools`）**没有**放在这个模块，而是放在了 `agent-runtime-api` 里。原因是 server 角色需要用到 `AgentRunService`，而 `AgentRunService` 又需要 Temporal 的 `WorkflowClient` —— 如果把 server 角色也放进 `agent-runtime-mcp`，就会出现 `agent-runtime-mcp → agent-runtime-temporal → agent-runtime-mcp` 的模块循环依赖（`agent-runtime-temporal` 已经因为 client 角色而依赖了 `agent-runtime-mcp`）。所以 server 角色干脆放进依赖链最下游的 `agent-runtime-api`，两个方向的代码互不干扰。

## `McpToolCallbackRegistry`

解析外部 MCP 服务器暴露出来的工具列表，转成跟本地 `@Tool` 一样的 `ToolCallback` 接口 —— 这样 `agent-runtime-temporal` 里的 `CompositeToolResolver` 就能把本地工具和 MCP 远程工具用同一套逻辑统一处理，Workflow/Activity 层完全不需要关心某个工具名到底是本地实现还是来自某个外部 MCP 服务器。

配置外部 MCP 客户端连接走 `application.yml` 的 `spring.ai.mcp.client` 配置段（client 角色是可选启用的，默认注释掉），比如用官方参考的 filesystem server：

```yaml
spring:
  ai:
    mcp:
      client:
        stdio:
          connections:
            filesystem:
              command: npx
              args: ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
```
