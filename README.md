# Agent Runtime

A general-purpose, multi-tenant multi-agent orchestration platform built on **Spring Boot 4 + Spring AI 2** and **Temporal**, backed by **PostgreSQL** (conversations, agent registry, pgvector) and **Redis** (SSE streaming bridge, cache/session, distributed locks). A separate **Python `voice-worker`** service (LiveKit Agents) adds realtime voice calls with a lip-synced digital-human avatar on top of the same backend.

It is not a single fixed chatbot: agents are registered data (`agent_definitions`), each run is a durable Temporal workflow, and every LLM call / tool call / persistence write is an independently retryable Temporal Activity — visible step by step in the Temporal Web UI.

## Architecture at a glance

```
Web chat UI (static/index.html)          Voice call (browser mic + LiveKit)
  │  POST /api/v1/agent-runs                │  POST /api/v1/voice-sessions
  │  GET  /api/v1/agent-runs/{id}/stream    │  (LiveKit room token)
  ▼                                         ▼
agent-runtime-api  (Spring Boot: REST + SSE + Temporal Worker + MCP server)
  │  starts / signals / queries                    LiveKit Cloud room
  ▼                                                        │ auto-dispatch
Temporal Server ────────────────────────────       voice-worker (Python)
  │  AgentRunWorkflow (ReAct loop)                  ElevenLabs STT/TTS
  │    ├─ LlmActivities.callChatModel   ◄──HTTP+SSE── BackendLLM bridge
  │    ├─ ToolActivities.invokeTool                  Spatius avatar (video)
  │    ├─ PersistenceActivities.*
  │    └─ StreamingActivities.publishEvent → Redis pub/sub → SSE
  ▼
PostgreSQL (+ pgvector)          Redis (pub/sub bridge)
```

Why Temporal drives the loop instead of Spring AI's built-in tool-calling: `ChatModel.stream()` is invoked directly (bypassing `ChatClient`'s auto tool-execution) so that **every LLM call and every tool call is its own Activity**, with independent retry policies and full visibility in Temporal's event history. The Workflow code stays deterministic (no direct I/O); all I/O — including streaming progress to Redis — happens inside Activities.

## Module layout (Maven multi-module, Java 21)

| Module | Depends on | Contents |
|---|---|---|
| `agent-runtime-common` | — | Cross-module DTOs (records), enums (`LlmProvider`, `AgentRunStatus`, `MessageRole`), `LlmModelConfig`, `TenantContext`, `ProgressEventPublisher` interface, exceptions |
| `agent-runtime-persistence` | common | JPA entities (`Tenant`, `AgentDefinition`, `LlmModel`, `AgentRun`, `Conversation`, `ConversationMessage`), Spring Data repositories, Flyway migrations |
| `agent-runtime-llm` | common | `ChatModelResolver` (builds/caches a `ChatModel` client per `llm_models` DB row), local `@Tool`s (`TimeTools`, `WebSearchTools`), `ToolCallbackRegistry` |
| `agent-runtime-mcp` | common | MCP **client** role — `McpToolCallbackRegistry` resolves tools exposed by external MCP servers |
| `agent-runtime-temporal` | common, persistence, llm, mcp | Workflow/Activity interfaces + implementations, `WorkflowIdFactory`, `SearchAttributeKeys`, `TemporalQueues` (not independently runnable) |
| `agent-runtime-api` | temporal (transitively: all) | The only runnable Spring Boot app: REST controller, SSE streaming, Redis pub/sub bridge, tenant context filter, MCP **server** role, voice session bootstrap (`VoiceSessionController`), static web chat + voice-call UI, Temporal Worker (co-located for local dev) |

Internal dependency direction is a straight line (`common → persistence → llm → mcp → temporal → api`) — no cycles. MCP server-role tools live in `agent-runtime-api` rather than `agent-runtime-mcp`, specifically to avoid a `mcp → temporal → mcp` cycle (server-role tools need `AgentRunService`, which needs the Temporal `WorkflowClient`).

`voice-worker/` (Python) sits outside the Maven reactor entirely — see [Realtime voice](#realtime-voice-livekit--elevenlabs--spatius) below.

## Key design decisions

- **Tenant isolation is explicit, never `ThreadLocal` beyond the request thread.** `TenantContext` (ThreadLocal) is populated by `TenantContextFilter` and read once per request via `@TenantId` on controller method parameters. From there down — service, Temporal `AgentRunInput`, every Activity request DTO, every repository method — `tenantId` is passed as an explicit argument. Temporal Workflow/Activity threads are pooled and shared across tenants, so a ThreadLocal at that layer would be a cross-tenant data leak waiting to happen.
- **LLM models are DB-driven, not fixed Spring beans.** An `llm_models` row (`provider`, `base_url`, `api_key_env`, `model_name`) is a named, reusable endpoint config; `agent_definitions.llm_model_id` points at one. `ChatModelResolver` builds (and caches) a `ChatModel` client per distinct `LlmModelConfig` using the provider's own Java SDK builders directly — this lets two agents both configured as `provider=OPENAI` point at completely different OpenAI-compatible endpoints/keys (e.g. one at DeepSeek, one at GLM) simultaneously, and adding/repointing a model is a data change with **zero app restart**.
- **Workflow ID** = `tenant-{tenantId}-agentrun-{agentRunId}` (see `WorkflowIdFactory`), plus custom Temporal **Search Attributes** `TenantId` and `AgentKey` (Keyword type), so runs are queryable per tenant directly from `temporal workflow list --query "TenantId='acme-corp'"` or the Web UI.
- **Streaming is Activity → Redis → SSE, not Workflow → Redis directly**, and it's real token-level streaming. `LlmActivitiesImpl` calls `chatModel.stream(...)` and publishes each text delta as an `LLM_DELTA` progress event as it arrives (via Spring AI's `MessageAggregator`, which still hands back one fully-merged `ChatResponse` — including reassembled tool calls — once the stream ends, so the Workflow's ReAct loop logic is untouched). `StreamingActivities` (Activity, runs on the Worker JVM) publishes to a per-run Redis channel (`agent-events:{tenantId}:{runId}`), and `RedisSubscriptionBridge` in the API relays it to any SSE connection — including on a *different* API instance than the one running the Worker. The same `LLM_DELTA` events are what the voice pipeline turns into TTS audio (see below).
- **Agent definitions are data, not code.** `agent_definitions` rows carry `llm_model_id`, `system_prompt`, `max_iterations`, `tool_names` — new agents are registered via SQL/API, not new Java classes. `tenant_id IS NULL` rows are global/shared demo agents (fallback in the lookup query `WHERE (tenant_id = :t OR tenant_id IS NULL) AND agent_key = :key`).
- **Multi-turn memory via `conversations`, not per-run isolation.** Each `POST /agent-runs` call is still one Temporal Workflow run (one ReAct loop), but passing a prior response's `conversationId` seeds the new run's history with that conversation's prior USER inputs and final ASSISTANT answers (tool-call-only intermediate messages and raw TOOL results are deliberately excluded from the carried-forward context — the model already acted on them in the turn where they happened).
- **Vector tenant isolation via metadata, not a native column.** `pgvector` rows carry `tenant_id` inside the `metadata` JSONB and are filtered via Spring AI's `SearchRequest.filterExpression(...)` — reuses the stock `VectorStore` abstraction with zero custom code, at the cost of no index-accelerated tenant filter (acceptable at current scale).
- **Human-in-the-loop is structurally present.** `AgentRunWorkflow` exposes `submitApproval` (signal) and `cancel` (signal) alongside `getProgress` (query), so a `WAITING_FOR_APPROVAL` pause/resume point is available even though the current demo agent doesn't require it.
- **Auth is out of scope for this phase.** `TenantContextFilter` trusts the `X-Tenant-Id` header as-is (only enforced on `/api/**`, not on static assets) — the filter is the single place a future auth layer should plug in to validate a real identity claim instead.

## Data model (Flyway, `agent-runtime-persistence/src/main/resources/db/migration/`)

| Migration | Table / change | Notes |
|---|---|---|
| `V1` | — | `CREATE EXTENSION vector` |
| `V2` | `tenants` | `tenant_key` (unique) auto-provisioned on first request per `X-Tenant-Id` |
| `V3` | `agent_definitions` | Agent registry: `tenant_id` nullable (NULL = global), `system_prompt`, `max_iterations`, `tool_names` (jsonb) |
| `V4` | `agent_runs` | `id` = agentRunId, `workflow_id` (unique), `status`, `input_payload`/`result_payload` (jsonb), `error_message` |
| `V5` | `conversation_messages` | `role`, `content`, `tool_name`, `tool_call_id`, `sequence_number` — full ReAct transcript |
| `V6` | `vector_store` | Hand-written to match Spring AI's default `PgVectorStore` DDL (HNSW + cosine), so it stays under Flyway instead of Spring AI's own `initialize-schema` |
| `V7` | — | Seeds one global demo agent: `general-assistant` |
| `V8` | — | Adds `web_search` to the demo agent's `tool_names` |
| `V9` | `llm_models` | Extracts `llm_provider`/`llm_model` off `agent_definitions` into a standalone, reusable table (`provider`, `base_url`, `api_key_env`, `model_name`); `agent_definitions.llm_model_id` now references it |
| `V10` | `conversations` | Groups multiple `agent_runs` (one per turn) into one ongoing conversation; `agent_runs.conversation_id` references it |

## Tech stack

- **Java 21**, Maven multi-module, DTOs as `record`s (no Lombok)
- **Spring Boot 4.1.1** / **Spring AI 2.0.1** (new `spring-ai-starter-*` artifact naming)
- **Temporal Java SDK 1.37.0** + `temporal-spring-boot-starter` (Worker auto-registered in `agent-runtime-api`)
- **PostgreSQL 16** (`pgvector/pgvector:pg16` image) — application data; a **separate** `postgres:16-alpine` instance backs Temporal's own internal schema
- **Redis 7** — pub/sub streaming bridge
- **MCP** (Model Context Protocol) — dual role: client (consume external tool servers) and server (`invoke_agent` / `get_agent_run_result` tools exposing this platform's agents)
- **Flyway** — all schema, including the vector table, under one migration tool
- **Testcontainers** — persistence-layer integration tests
- **Tavily** — backs the `web_search` tool
- **LiveKit + ElevenLabs + Spatius** (Python `voice-worker`) — realtime voice calls with an avatar

Notable Spring Boot 4 / Spring AI 2 friction points worth knowing before touching this code:
- Spring Boot 4's modularized autoconfiguration means `flyway-core` alone no longer triggers `FlywayAutoConfiguration` — this project uses `spring-boot-starter-flyway` (see `agent-runtime-persistence/pom.xml`).
- Spring Boot 4 defaults to Jackson 3 (`tools.jackson.*`, immutable `JsonMapper`) with **no** auto-configured `com.fasterxml.jackson.databind.ObjectMapper` bean. This codebase still uses Jackson 2 explicitly; `JacksonConfig` (`agent-runtime-api`) wires the bean by hand.
- Each concrete Spring AI `ChatModel` (`OpenAiChatModel`/`AnthropicChatModel`/`OllamaChatModel`) casts `Prompt.getOptions()` to its **own** provider-specific `ChatOptions` subtype internally — building options via the generic `ToolCallingChatOptions.builder()` throws a `ClassCastException` at call time. `LlmActivitiesImpl.buildOptions(...)` switches on provider and uses each one's own builder.
- Building a `ChatModel` dynamically (rather than letting Spring auto-configure one) requires supplying **both** the sync and async provider clients explicitly (`OpenAiChatModel.builder().openAiClient(...).openAiClientAsync(...)`) — if you omit the async client, the builder tries to derive one from ambient environment variables and throws `"At least one credential source must be specified"` even though the sync client is fully configured. See `ChatModelResolver`.
- `spring.ai.model.embedding: openai` is set explicitly because `PgVectorStoreAutoConfiguration` requires exactly one `EmbeddingModel` bean.

## Local development environment

`docker-compose.yml` brings up:

| Service | Image | Port | Purpose |
|---|---|---|---|
| `postgres` | `pgvector/pgvector:pg16` | `5432` | Application database |
| `temporal-postgresql` | `postgres:16-alpine` | `5433` | Temporal server's own schema (decoupled from the app DB) |
| `temporal` | `temporalio/auto-setup:1.29.1` | `7233` | Temporal Server (gRPC/SDK) |
| `temporal-setup-search-attributes` | `temporalio/admin-tools:1.29.1-...` | — | One-shot: registers `TenantId`/`AgentKey` search attributes (safe to re-run) |
| `temporal-ui` | `temporalio/ui:2.34.0` | `8088` | Temporal Web UI (mapped off 8080 to avoid clashing with the API) |
| `redis` | `redis:7-alpine` | `6380` (host) → `6379` | Pub/sub streaming bridge (remapped from 6379 to dodge a local port conflict) |
| `ollama` | `ollama/ollama` | `11434` | Optional, profile `local-llm` |

```bash
# start core infra
docker compose up -d postgres temporal-postgresql temporal temporal-setup-search-attributes temporal-ui redis

# optional local LLM
docker compose --profile local-llm up -d ollama
```

## Configuration (`agent-runtime-api/src/main/resources/application.yml`)

| Env var | Default | Purpose |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | `localhost` / `5432` / `agent_runtime` / `agent_runtime` / `agent_runtime` | Application Postgres |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6380` | Redis (matches the compose port remap) |
| `TEMPORAL_TARGET` | `127.0.0.1:7233` | Temporal Server gRPC endpoint |
| `OPENAI_API_KEY` | — | Embeddings only (`text-embedding-3-small`, platform-wide, independent of any agent's chat model) |
| `TAVILY_API_KEY` | — | Backs the `web_search` tool; without it the tool still registers but fails at call time |
| `LIVEKIT_API_KEY` / `LIVEKIT_API_SECRET` / `LIVEKIT_URL` | — | Only needed for realtime voice; `VoiceSessionController` uses these to issue room tokens |

**Per-agent chat models are not configured here at all.** Each `llm_models` row carries its own `base_url` + `api_key_env` (the *name* of an environment variable, e.g. `OPENAI_CHAT_API_KEY` or `DEEPSEEK_API_KEY` — never a raw key in the DB) + `model_name`; `ChatModelResolver` reads that env var at call time via `System.getenv(...)`. To point an agent at a new provider: insert an `llm_models` row, export the env var it references, update `agent_definitions.llm_model_id` — no code change, no restart.

**Never paste raw API keys directly in chat/terminal history that gets logged** — export them into your own shell session first, then launch the app from that same shell.

## Running

```bash
mvn -pl agent-runtime-api -am spring-boot:run
```

On startup: Flyway runs all migrations (creating `tenants`, `agent_definitions`, `llm_models`, `agent_runs`, `conversations`, `conversation_messages`, `vector_store`, plus one seeded demo `llm_models` row and the `general-assistant` agent pointed at it), and the Temporal Worker registers against task queue `agent-runtime-default`.

Open **http://localhost:8080** for the built-in web chat UI (with a 🎙️ voice-call button, if `voice-worker` is also running).

## API

All `/api/**` endpoints require `X-Tenant-Id` (missing/blank → `400`; tenants are auto-provisioned on first use). Cross-tenant access to another tenant's run returns `404`, enforced at the repository layer via explicit `tenantId`-scoped queries.

```bash
# submit a run (omit conversationId to start a new conversation)
curl -X POST http://localhost:8080/api/v1/agent-runs \
  -H "X-Tenant-Id: acme-corp" -H "Content-Type: application/json" \
  -d '{"agentKey":"general-assistant","input":"What time is it?"}'
# → 202 {"runId": "...", "conversationId": "...", "status": "PENDING"}

# continue the same conversation (model retains prior turns' context)
curl -X POST http://localhost:8080/api/v1/agent-runs \
  -H "X-Tenant-Id: acme-corp" -H "Content-Type: application/json" \
  -d '{"agentKey":"general-assistant","input":"and in Tokyo?","conversationId":"<conversationId from above>"}'

# poll status/result
curl -H "X-Tenant-Id: acme-corp" http://localhost:8080/api/v1/agent-runs/{runId}

# stream progress (SSE) — replays persisted history first, then live events
curl -N -H "X-Tenant-Id: acme-corp" http://localhost:8080/api/v1/agent-runs/{runId}/stream
```

SSE event sequence: `history` (replayed past messages) → `status` (current status) → live `progress` events: `RUN_STARTED → LLM_DELTA (×many, token-level) → LLM_THOUGHT → TOOL_CALL_STARTED → TOOL_CALL_COMPLETED → LLM_DELTA... → RUN_COMPLETED` (or `RUN_FAILED`).

## MCP

- **Client role** (`agent-runtime-mcp`): consumes external MCP tool servers via `McpToolCallbackRegistry`; wire a real server under `spring.ai.mcp.client` in `application.yml` (a commented-out example using the reference `@modelcontextprotocol/server-filesystem` is included).
- **Server role** (`agent-runtime-api`, `AgentInvocationTools`): exposes this platform's own agents as MCP tools — `invoke_agent(tenantId, agentKey, taskInput, conversationId?)` and `get_agent_run_result(tenantId, runId)` — so external MCP clients can drive agent runs through the same `AgentRunService` the REST API uses.

## Realtime voice (LiveKit + ElevenLabs + Spatius)

Voice calls run through a separate Python service, `voice-worker/`, because `livekit-agents` (the STT→LLM→TTS orchestration framework) only ships Python/Node.js SDKs — there is no Java equivalent. **All reasoning and tool calling still happens in the Java backend**; the worker is purely the voice input/output layer.

```
Browser mic (LiveKit JS SDK, vendored at static/vendor/livekit-client.min.js)
  │  WebRTC
  ▼
LiveKit room  ──auto-dispatch──▶  voice-worker (Python, livekit-agents)
                                    STT: ElevenLabs
                                    LLM: BackendLLM  ──HTTP POST + SSE──▶ agent-runtime-api
                                    TTS: ElevenLabs                        (same /agent-runs API
                                    Avatar: Spatius (video)                 the text chat uses)
```

1. Browser clicks "🎙️ 语音通话" → `POST /api/v1/voice-sessions {agentKey}` → `VoiceSessionController` creates a `Conversation` and returns a LiveKit room token (with `tenantId`/`agentKey`/`conversationId` embedded in the token's participant metadata).
2. Browser joins the LiveKit room directly with that token; LiveKit auto-dispatches `voice-worker` into the room.
3. `voice-worker` reads `tenantId`/`agentKey`/`conversationId` back out of the caller's participant metadata, then for each user utterance: ElevenLabs STT transcribes it → `backend_llm.py`'s `BackendLLM` (a custom `livekit.agents.llm.LLM` implementation) `POST`s it to `/api/v1/agent-runs` and reads the SSE stream, translating each `LLM_DELTA` event into a LiveKit `ChatChunk` in real time — so ElevenLabs TTS can start speaking before the full answer has finished generating — → Spatius renders it as a lip-synced avatar video track.
4. The same `conversationId` is shared with the browser's typed chat, so a voice call and typed messages continue the same multi-turn conversation.

See `voice-worker/README.md` for setup, required env vars (`LIVEKIT_*`, `ELEVEN_API_KEY`, `SPATIUS_*`), and a documented troubleshooting section for a known avatar-video issue (see below).

**Known issue (as of this writing): Spatius avatar video renders as a black box.** Confirmed via `RTCPeerConnection.getStats()` in the browser: audio works perfectly, but the video `inbound-rtp` report shows `framesDecoded: 0` with `framesDropped` equal to `framesReceived` — every video packet arrives but not one frame decodes. This is not a browser autoplay-block issue and not fixable from this repo's frontend code; the video bitstream is corrupted before it reaches the browser. Pinning `SPATIUS_REGION=us-west` (instead of `auto`) did not fix it. This needs to go to Spatius support with the WebRTC stats above. **Voice input/output (STT → backend reasoning → TTS) works correctly independent of this** — verified end-to-end with real speech, including real tool calls executed by the Temporal backend.

Client-side (`@livekit/krisp-noise-filter`) or server-side (`livekit-plugins-noise-cancellation`, Krisp BVC) noise cancellation was investigated but deliberately not wired in yet — Krisp BVC requires LiveKit Cloud and incurs additional billing (in effect since May 2026); add it once real background-noise problems are observed in practice, not preemptively.

## Web chat UI (`agent-runtime-api/src/main/resources/static/index.html`)

A single-page vanilla-JS chat UI (no build step, no framework) served directly as a Spring static resource:
- Streams `LLM_DELTA` tokens into the chat bubble live as they arrive (not just at completion).
- Renders a minimal markdown subset (headers, bold/italic, code, links, lists) — output is escaped first, so nothing the model or a `web_search` result quotes can inject markup.
- Tool call results (e.g. `web_search`, which can return long text) are collapsed by default behind a `<details>` disclosure.
- Multi-turn: reuses `conversationId` across sends within the page session.
- 🎙️ voice-call button: bootstraps a LiveKit room via `/api/v1/voice-sessions` and joins it with the vendored `livekit-client` SDK, rendering the Spatius avatar's video/audio tracks as they're subscribed. Handles the browser's autoplay-block detection (`room.canPlaybackAudio`/`canPlaybackVideo`) with a "click to enable" recovery prompt.

## Inspecting runs in Temporal

Temporal Web UI: http://localhost:8088

```bash
temporal workflow list --address 127.0.0.1:7233 --query "TenantId='acme-corp'"
```

Each run's event history shows every `CallChatModel` / `InvokeTool` / `AppendMessage` / `UpdateRunStatus` / `PublishProgressEvent` Activity individually — including retries and failures.

## Status / what's verified

Verified end-to-end with real infrastructure and real LLM/tool calls: tenant auto-provisioning, agent definition + dynamic LLM model resolution (including two different agents pointed at different OpenAI-compatible providers/keys simultaneously with zero restart), Temporal workflow execution with real tool calls (`get_current_time`, `web_search` via Tavily), token-level `LLM_DELTA` streaming end-to-end into the web UI, multi-turn conversation memory (verified the model actually recalls prior-turn facts), tenant isolation (400/404 cases), and the full realtime voice pipeline (real speech → ElevenLabs STT → Java/Temporal backend reasoning with a real tool call → ElevenLabs TTS) confirmed via both a live browser test and headless WebRTC-stats-level verification.

Not yet exercised / known gaps: Spatius avatar video rendering (see Known issue above — audio-only voice works fine), cross-JVM-instance SSE relay test, an external MCP client integration test against a live reference server, precise voice-call latency measurement (time from end-of-speech to first audio out).
