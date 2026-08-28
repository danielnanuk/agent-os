"""LiveKit Agents worker entrypoint for realtime voice calls.

Assembles the STT -> LLM -> TTS pipeline (ElevenLabs STT/TTS, BackendLLM bridging to
the Java agent-runtime backend) and routes the agent's synthesized audio through a
Spatius avatar instead of publishing it directly, so callers see a lip-synced digital
human rather than just hearing audio.

tenantId/agentKey/conversationId for the call are read from the caller participant's
token metadata (set by VoiceSessionController when it issues the room access token),
not hardcoded here -- one worker deployment serves every tenant/agent.

API shapes verified against livekit-agents 1.7.1 and livekit-plugins-spatius 1.7.1 via
`inspect` -- re-verify against the installed versions if this file needs to change.
"""

from __future__ import annotations

import json
import logging
import os

from dotenv import load_dotenv
from livekit.agents import (
    Agent,
    AgentSession,
    JobContext,
    RoomOutputOptions,
    WorkerOptions,
    cli,
)
from livekit.plugins import elevenlabs
from livekit.plugins import silero
from livekit.plugins import spatius

from backend_llm import BackendLLM

load_dotenv()  # picks up ./.env if present; explicit shell exports still take precedence

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("voice-worker")

BACKEND_BASE_URL = os.environ.get("AGENT_RUNTIME_BASE_URL", "http://localhost:8080")
SPATIUS_AVATAR_ID = os.environ.get("SPATIUS_AVATAR_ID")


async def entrypoint(ctx: JobContext) -> None:
    await ctx.connect()
    participant = await ctx.wait_for_participant()

    try:
        metadata = json.loads(participant.metadata) if participant.metadata else {}
    except json.JSONDecodeError:
        logger.error("participant metadata is not valid JSON: %r", participant.metadata)
        metadata = {}

    tenant_id = metadata.get("tenantId")
    agent_key = metadata.get("agentKey")
    conversation_id = metadata.get("conversationId")
    if not tenant_id or not agent_key:
        raise RuntimeError(f"room token metadata missing tenantId/agentKey: {metadata!r}")

    logger.info("voice call starting: tenant=%s agent=%s conversation=%s",
                tenant_id, agent_key, conversation_id)

    backend_llm = BackendLLM(
        base_url=BACKEND_BASE_URL,
        tenant_id=tenant_id,
        agent_key=agent_key,
        conversation_id=conversation_id,
    )

    session = AgentSession(
        stt=elevenlabs.STT(),
        # ElevenLabs STT (non-realtime mode) needs an external VAD to know where one
        # utterance ends and the next begins -- without it, audio never gets chunked
        # into a transcription request and the pipeline silently never fires.
        vad=silero.VAD.load(),
        llm=backend_llm,
        # Spatius's Ogg Opus avatar encoding only accepts 8k/12k/16k/24k/48k Hz;
        # ElevenLabs TTS defaults to 22050 Hz (mp3), which fails avatar.start(). Force
        # a PCM encoding at a rate Spatius supports.
        tts=elevenlabs.TTS(encoding="pcm_24000"),
    )

    avatar = spatius.AvatarSession(avatar_id=SPATIUS_AVATAR_ID) if SPATIUS_AVATAR_ID else None
    if avatar is not None:
        await avatar.start(session, ctx.room)

    await session.start(
        agent=Agent(instructions="Speak naturally; the actual reasoning happens in the backend."),
        room=ctx.room,
        # When an avatar is attached, its audio (lip-synced to the video) is what gets
        # published -- the agent's own synthesized audio must NOT also go out directly,
        # or the room would hear the voice twice.
        room_output_options=RoomOutputOptions(audio_enabled=avatar is None),
    )


if __name__ == "__main__":
    cli.run_app(WorkerOptions(entrypoint_fnc=entrypoint))
