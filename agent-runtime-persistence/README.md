# agent-runtime-persistence

JPA 实体、Spring Data 仓储、Flyway 数据库迁移。这个模块只依赖 `agent-runtime-common`，不依赖 Temporal 或 Spring AI —— 纯粹是数据访问层。

## 目录结构

```
persistence/entity/       JPA 实体（普通类，不用 record —— record 不适合做可变的 JPA 实体）
persistence/repository/   Spring Data JpaRepository 接口
resources/db/migration/   Flyway 迁移脚本 V1~V10
```

## 实体与表

| 实体 | 表 | 说明 |
|---|---|---|
| `Tenant` | `tenants` | `tenant_key` 唯一，首次出现某个 `X-Tenant-Id` 时自动创建 |
| `AgentDefinition` | `agent_definitions` | Agent 注册表；`tenantId` 可为 `null`（表示全局共享）；`llmModelId` 指向 `LlmModel` |
| `LlmModel` | `llm_models` | 一个可复用、有名字的 LLM 端点配置（`provider`/`baseUrl`/`apiKeyEnv`/`modelName`），`tenantId` 同样可为 `null` |
| `AgentRun` | `agent_runs` | 一次 Agent 运行（= 一次 Temporal Workflow 执行），`conversationId` 指向 `Conversation` |
| `Conversation` | `conversations` | 把多次 `AgentRun`（每轮一次）归到同一个持续对话下，支撑多轮记忆 |
| `ConversationMessage` | `conversation_messages` | 完整的 ReAct 消息记录（USER/ASSISTANT/TOOL），按 `sequenceNumber` 排序 |

另外 `V6` 迁移手写了一个 `vector_store` 表，跟 Spring AI 默认 `PgVectorStore` 的 DDL 保持一致（HNSW 索引 + cosine 距离），但没有对应的 JPA 实体 —— 这张表完全通过 Spring AI 自己的 `VectorStore` 抽象读写（在 `agent-runtime-llm` 模块里配置），之所以还要把它的建表语句放进 Flyway，是为了让所有 schema 变更都归一套迁移工具管理，而不是让 Spring AI 用它自己的 `initialize-schema` 机制在应用启动时另外建表。

## 多租户隔离的强制手段

**所有仓储方法都显式带 `tenantId` 参数**（`findByIdAndTenantId`、`findByConversationIdAndTenantIdOrderByCreatedAtAsc` 这类命名），这是保证租户隔离的**唯一**机制 —— 没有用 Hibernate 的 `@Filter`。原因很直接：一个忘记开启的 `@Filter` 是静默的跨租户数据泄露，而一个漏传的 `tenantId` 参数是编译错误。`@Filter` 可以作为纵深防御的补充手段，但绝不应该是唯一依赖的机制。

## Flyway 迁移一览

| 迁移 | 内容 |
|---|---|
| `V1` | `CREATE EXTENSION vector` |
| `V2` | 建 `tenants` 表 |
| `V3` | 建 `agent_definitions` 表 |
| `V4` | 建 `agent_runs` 表 |
| `V5` | 建 `conversation_messages` 表 |
| `V6` | 手写 `vector_store` 表（HNSW + cosine） |
| `V7` | 种一个全局演示 Agent `general-assistant` |
| `V8` | 给演示 Agent 加 `web_search` 工具 |
| `V9` | 把 `llm_provider`/`llm_model` 从 `agent_definitions` 抽成独立的 `llm_models` 表 |
| `V10` | 建 `conversations` 表，`agent_runs` 加 `conversation_id` 外键 |

新增迁移时记得：文件名必须是 `V{下一个序号}__{描述}.sql`，`ddl-auto` 配的是 `validate`（不是 `update`），所以 JPA 实体的字段变更必须配一条对应的 Flyway 迁移，否则应用启动时 Hibernate 的 schema 校验会直接失败。
