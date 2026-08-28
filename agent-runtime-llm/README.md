# agent-runtime-llm

Spring AI 集成层：动态构建 LLM 客户端、本地工具（`@Tool`）注册。依赖 `agent-runtime-common` 和 `agent-runtime-persistence`。

## 目录结构

```
llm/resolver/   ChatModelResolver —— 按需构建并缓存 ChatModel 客户端
llm/tool/       本地 @Tool 实现 + ToolCallbackRegistry
```

## `ChatModelResolver`：为什么不用 Spring 自动配置的 Bean

最直接的做法是让 Spring AI 给 OpenAI/Anthropic/Ollama 各自动配置一个 `ChatModel` Bean，用的时候按 provider 类型注入对应的。但这样每个 provider 只能有**一份**全局配置（一个 base-url、一个 key）—— 没法让两个都配置成 `provider=OPENAI` 的 Agent 同时指向不同的 OpenAI 兼容端点（比如一个用 DeepSeek，一个用智谱 GLM）。

`ChatModelResolver` 改成了完全动态的方式：`agent_definitions.llm_model_id` 指向 `llm_models` 表的一行（`provider`/`baseUrl`/`apiKeyEnv`/`modelName`），`ChatModelResolver.resolve(LlmModelConfig)` 直接用每个供应商**自己的** Java SDK builder（`OpenAIClientImpl`/`AnthropicClientImpl`/`OllamaApi`）现场构建一个 `ChatModel`，按 `LlmModelConfig`（一个 record，天然可作缓存 key）缓存起来，同一份配置只会真正构建一次底层 HTTP 客户端。

踩过的坑，写在代码注释里但这里也提一下：
- 每个具体的 `ChatModel` 内部都会把 `Prompt.getOptions()` 强转成自己专属的 Options 子类，用通用的 `ToolCallingChatOptions.builder()` 会在调用时直接抛 `ClassCastException` —— 必须用各 provider 自己的 builder（`OpenAiChatOptions.builder()` 等）。
- 动态构建 `OpenAiChatModel`/`AnthropicChatModel` 时，如果只提供同步客户端、不提供异步客户端，builder 会尝试从环境变量兜底推导一个异步客户端，即使同步客户端配置完整也会抛 `"At least one credential source must be specified"` —— 必须用同一份 `ClientOptions` 显式构建出两个客户端一起传进去。
- API key 只存**环境变量名**（`llm_models.api_key_env`），运行时用 `System.getenv(...)` 读，数据库里永远不落地明文密钥。

## 本地工具（`@Tool`）

- **`TimeTools.get_current_time`** —— 最简单的演示工具，返回当前 ISO-8601 时间。
- **`WebSearchTools.web_search`** —— 调 Tavily 的 `/search` 接口，返回摘要过的搜索结果给模型。需要 `TAVILY_API_KEY`；没配的话工具照样能注册（不会破坏引用它的 Agent 定义），只是调用时会失败。

所有工具类都要实现 `AgentTool` 这个标记接口，这样 `ToolCallbackRegistry` 才能通过 `List<AgentTool>` 依赖注入把它们全部收集起来（而不是扫描 Spring 容器里所有的 Bean）。工具名和 `ToolCallback` 的映射关系是 `ToolCallbackRegistry` 提供的唯一权威来源 —— `LlmActivities` 用它把工具 schema 报给模型，`ToolActivities` 用它真正执行工具调用，两边各自在独立的 Temporal Activity 里进行。
