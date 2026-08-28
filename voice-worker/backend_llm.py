"""Bridges LiveKit Agents' LLM interface to the Java/Temporal agent-runtime backend.

All reasoning and tool calling happens in the Java backend's AgentRunWorkflow -- this
class does no inference itself. It forwards the latest user utterance to
POST /api/v1/agent-runs and relays the LLM_DELTA progress events from
GET /api/v1/agent-runs/{id}/stream back as streaming ChatChunks, so LiveKit's TTS can
start speaking mid-answer instead of waiting for the full response.

API shapes verified against livekit-agents 1.7.1 via `inspect` (see project notes) --
do not assume they match older docs/blog posts if this file needs to change later.
"""

from __future__ import annotations

import json
import logging
import uuid

import aiohttp
from livekit.agents import APIConnectionError, APIConnectOptions, DEFAULT_API_CONNECT_OPTIONS
from livekit.agents.llm import LLM, LLMStream
from livekit.agents.llm.llm import ChatChunk, ChoiceDelta
from livekit.agents.llm.chat_context import ChatContext, ChatMessage
from livekit.agents.types import NOT_GIVEN, NotGivenOr

logger = logging.getLogger("voice-worker.backend_llm")


class BackendLLM(LLM):
    def __init__(self, *, base_url: str, tenant_id: str, agent_key: str,
                 conversation_id: str | None = None) -> None:
        super().__init__()
        self._base_url = base_url.rstrip("/")
        self._tenant_id = tenant_id
        self._agent_key = agent_key
        # Mutated after each turn by BackendLLMStream so the next turn continues the
        # same conversation -- there is exactly one BackendLLM per voice call, so this
        # is safe without extra locking (turns are sequential, never concurrent).
        self.conversation_id = conversation_id

    @property
    def model(self) -> str:
        return self._agent_key

    @property
    def provider(self) -> str:
        return "agent-runtime"

    def chat(
        self,
        *,
        chat_ctx: ChatContext,
        tools: list | None = None,
        conn_options: APIConnectOptions = DEFAULT_API_CONNECT_OPTIONS,
        parallel_tool_calls: NotGivenOr[bool] = NOT_GIVEN,
        tool_choice: NotGivenOr[object] = NOT_GIVEN,
        extra_kwargs: NotGivenOr[dict] = NOT_GIVEN,
    ) -> LLMStream:
        # Tool calling is handled entirely inside the Java backend's ReAct loop, so
        # `tools` (LiveKit-side function tools) is intentionally not forwarded.
        return BackendLLMStream(self, chat_ctx=chat_ctx, tools=[], conn_options=conn_options)


class BackendLLMStream(LLMStream):
    def __init__(self, llm: BackendLLM, *, chat_ctx: ChatContext, tools: list,
                 conn_options: APIConnectOptions) -> None:
        super().__init__(llm, chat_ctx=chat_ctx, tools=tools, conn_options=conn_options)
        self._backend_llm = llm

    async def _run(self) -> None:
        llm = self._backend_llm
        user_text = self._latest_user_text()
        if not user_text:
            return

        chunk_id = str(uuid.uuid4())
        headers = {"X-Tenant-Id": llm._tenant_id}

        try:
            async with aiohttp.ClientSession() as session:
                submit_body = {
                    "agentKey": llm._agent_key,
                    "input": user_text,
                    "conversationId": llm.conversation_id,
                }
                async with session.post(
                    f"{llm._base_url}/api/v1/agent-runs",
                    json=submit_body,
                    headers={**headers, "Content-Type": "application/json"},
                    timeout=aiohttp.ClientTimeout(total=self._conn_options.timeout),
                ) as resp:
                    if resp.status != 202:
                        body = await resp.text()
                        raise APIConnectionError(f"agent-runs submit failed ({resp.status}): {body}")
                    data = await resp.json()

                run_id = data["runId"]
                llm.conversation_id = data["conversationId"]
                logger.debug("started run %s in conversation %s", run_id, llm.conversation_id)

                stream_url = f"{llm._base_url}/api/v1/agent-runs/{run_id}/stream"
                async with session.get(stream_url, headers=headers) as resp:
                    if resp.status != 200:
                        body = await resp.text()
                        raise APIConnectionError(f"agent-runs stream failed ({resp.status}): {body}")
                    await self._consume_sse(resp, chunk_id)
        except aiohttp.ClientError as e:
            raise APIConnectionError(str(e)) from e

    async def _consume_sse(self, resp: aiohttp.ClientResponse, chunk_id: str) -> None:
        buffer = ""
        async for raw_bytes in resp.content.iter_any():
            buffer += raw_bytes.decode("utf-8")
            while "\n\n" in buffer:
                raw_event, buffer = buffer.split("\n\n", 1)
                event_name, data_line = self._parse_sse_event(raw_event)
                if not data_line:
                    continue
                payload = json.loads(data_line)
                if event_name != "progress":
                    # 'history'/'status' are replay-only for reconnects; every voice
                    # turn here is a brand-new run, so there is nothing to replay.
                    continue
                if self._emit_progress(payload, chunk_id):
                    return  # terminal event -- stop reading the stream

    def _emit_progress(self, payload: dict, chunk_id: str) -> bool:
        """Returns True once a terminal event (RUN_COMPLETED/RUN_FAILED) is seen."""
        event_type = payload.get("eventType")
        if event_type == "LLM_DELTA":
            delta_text = payload.get("payload") or ""
            if delta_text:
                self._event_ch.send_nowait(
                    ChatChunk(id=chunk_id, delta=ChoiceDelta(role="assistant", content=delta_text))
                )
        elif event_type == "RUN_FAILED":
            error_text = payload.get("payload") or "抱歉，出错了。"
            logger.warning("agent run failed: %s", error_text)
            self._event_ch.send_nowait(
                ChatChunk(id=chunk_id, delta=ChoiceDelta(role="assistant", content=error_text))
            )
            return True
        elif event_type == "RUN_COMPLETED":
            return True
        # TOOL_CALL_STARTED / TOOL_CALL_COMPLETED / LLM_THOUGHT / RUN_STARTED: no-op for voice.
        return False

    def _latest_user_text(self) -> str | None:
        for message in reversed(self._chat_ctx.messages()):
            if isinstance(message, ChatMessage) and message.role == "user":
                return message.text_content
        return None

    @staticmethod
    def _parse_sse_event(raw_event: str) -> tuple[str, str]:
        event_name = "message"
        data_line = ""
        for line in raw_event.split("\n"):
            if line.startswith("event:"):
                event_name = line[len("event:"):].strip()
            elif line.startswith("data:"):
                data_line += line[len("data:"):].strip()
        return event_name, data_line
