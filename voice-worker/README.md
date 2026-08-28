# voice-worker

LiveKit Agents worker for realtime voice calls. This is a separate Python service
(not part of the Java/Maven build) because `livekit-agents` -- the STT→LLM→TTS
orchestration framework -- only ships Python/Node.js SDKs.

**All reasoning and tool calling happens in the Java `agent-runtime-api` backend.**
This worker is purely the voice input/output layer: it joins a LiveKit room, runs
ElevenLabs STT/TTS, forwards each user utterance to the Java backend over HTTP, and
relays the backend's streamed `LLM_DELTA` events back into LiveKit's TTS pipeline (see
`backend_llm.py`). A Spatius avatar renders the agent's speech as a lip-synced video
track (see `worker.py`).

## Setup

```bash
cd voice-worker
python3 -m venv .venv
source .venv/bin/activate
python3 -m pip install -e .
cp .env.example .env  # fill in real values; .env is gitignored, .env.example is not
```

## Configuration (environment variables)

| Var | Required | Purpose |
|---|---|---|
| `LIVEKIT_URL` | yes | LiveKit server WebSocket URL, e.g. `wss://your-project.livekit.cloud` |
| `LIVEKIT_API_KEY` / `LIVEKIT_API_SECRET` | yes | Same LiveKit project credentials the Java backend's `VoiceSessionController` uses to issue caller tokens |
| `ELEVEN_API_KEY` | yes | Read automatically by the `livekit-plugins-elevenlabs` STT/TTS classes (note: `ELEVEN_`, not `ELEVENLABS_`) |
| `AGENT_RUNTIME_BASE_URL` | no (default `http://localhost:8080`) | Base URL of the Java `agent-runtime-api` |
| `SPATIUS_AVATAR_ID` | no | If set, routes agent speech through a Spatius avatar instead of publishing raw audio directly |
| `SPATIUS_API_KEY` / `SPATIUS_APP_ID` | only if `SPATIUS_AVATAR_ID` is set | Read automatically by `livekit-plugins-spatius`'s `AvatarSession` |
| `SPATIUS_REGION` | no (default `auto`) | Pins the Spatius rendering/ingress region instead of letting their bootstrap API auto-select one. See "Avatar video is a black box" below. |

## Troubleshooting: avatar video is a black box (audio works fine)

Confirmed via browser WebRTC stats (`RTCPeerConnection.getStats()`) during testing: audio
plays normally, but the video `inbound-rtp` report shows `framesDecoded: 0` and
`framesDropped` equal to `framesReceived` -- every video packet arrives but not a single
frame decodes (the player just keeps sending PLI requests for a fresh keyframe that never
arrives intact). This is **not** a browser autoplay-block issue and not fixable from
`static/index.html` -- the video bitstream itself is corrupted before it reaches the
browser.

Spatius's `auto` region resolution picked `cn-beijing` in testing while the LiveKit Cloud
room was hosted in Tokyo (`Japan A`) -- a mismatched/long network path looked like a
plausible cause, so this was tested:

```bash
SPATIUS_REGION=us-west LIVEKIT_URL=... python3 worker.py dev
```

**Tried, did not fix it.** Re-running the same `getStats()` capture with `us-west` pinned
showed the identical failure pattern (`framesDecoded: 0`, 100% frame drop) -- if anything
the bytes-per-packet ratio was worse. This rules out region/routing as the cause and
points at something systemic in Spatius's encode pipeline (or an account/avatar-id
config issue), not fixable from this repo. Report it to Spatius support with the
`getStats()` numbers (`framesReceived`/`framesDecoded`/`framesDropped`/`pliCount`/
`bytesReceived` from the video `inbound-rtp` report) plus `avatar_id`/`app_id`.

## Manual verification without a real microphone (`test_call.py`)

A one-shot diagnostic script: connects to a LiveKit room as the caller (given a
`{roomName, livekitUrl, livekitToken}` JSON blob, e.g. from `POST /api/v1/voice-sessions`),
publishes a pre-recorded WAV as a fake microphone track, and logs room/participant/track
events -- so the whole pipeline (worker dispatch, STT, backend round trip, TTS, avatar
join) can be exercised end-to-end without a human or a real mic:

```bash
say "what time is it" -o /tmp/test_speech.aiff  # macOS; use any TTS/recording elsewhere
afconvert -f WAVE -d LEI16@16000 -c 1 /tmp/test_speech.aiff /tmp/test_speech.wav
python3 test_call.py /tmp/voice_session.json /tmp/test_speech.wav
```

This is also how the black-box-avatar issue above was actually diagnosed (paired with a
Playwright script wrapping `RTCPeerConnection` to pull `getStats()` -- not checked in
here since it's a throwaway diagnostic harness, not part of the worker).

## Running locally

1. Make sure `agent-runtime-api` (the Java backend) is running and reachable at `AGENT_RUNTIME_BASE_URL`.
2. Start the worker in dev mode (auto-reloads, verbose logging, connects to LiveKit and waits for room dispatches):

   ```bash
   LIVEKIT_URL=wss://... LIVEKIT_API_KEY=... LIVEKIT_API_SECRET=... \
   ELEVEN_API_KEY=... \
   python3 worker.py dev
   ```

3. Trigger a call: `POST /api/v1/voice-sessions {"agentKey": "general-assistant"}` on
   the Java backend to get a `{roomName, livekitToken, livekitUrl, conversationId}`.
   Connect any LiveKit client (e.g. the [Agents Playground](https://agents-playground.livekit.io/))
   with that token/URL -- LiveKit auto-dispatches this worker to the room once a
   participant joins.

## Notes on API stability

The exact `livekit-agents` / `livekit-plugins-elevenlabs` / `livekit-plugins-spatius`
class and method signatures used in `backend_llm.py` and `worker.py` were verified via
`python3 -c "import inspect; ..."` against the versions pinned in `pyproject.toml`
(`livekit-agents` 1.7.1, `livekit-plugins-spatius` 1.7.1) at the time this was written.
These are fast-moving packages -- if `pip install -e .` pulls a newer version and
something breaks, re-verify the relevant signatures the same way rather than guessing
from documentation, which lags releases.
