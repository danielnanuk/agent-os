# voice-worker

用于实时语音通话的 LiveKit Agents worker。这是一个独立的 Python 服务（不属于 Java/Maven 构建），因为 `livekit-agents`（STT→LLM→TTS 编排框架）官方只提供 Python/Node.js SDK。

**所有推理和工具调用都发生在 Java 的 `agent-runtime-api` 后端。** 这个 worker 纯粹是语音输入输出层：加入一个 LiveKit 房间、跑 ElevenLabs 的 STT/TTS、把每一句用户话语转发给 Java 后端（走 HTTP），再把后端流式返回的 `LLM_DELTA` 事件转接回 LiveKit 的 TTS 流水线（见 `backend_llm.py`）。Spatius 头像把 Agent 的语音渲染成对口型的视频轨道（见 `worker.py`）。

## 环境搭建

```bash
cd voice-worker
python3 -m venv .venv
source .venv/bin/activate
python3 -m pip install -e .
cp .env.example .env  # 填入真实值；.env 已加入 .gitignore，.env.example 没有
```

## 配置（环境变量）

| 变量 | 是否必需 | 用途 |
|---|---|---|
| `LIVEKIT_URL` | 是 | LiveKit 服务器的 WebSocket 地址，如 `wss://your-project.livekit.cloud` |
| `LIVEKIT_API_KEY` / `LIVEKIT_API_SECRET` | 是 | 跟 Java 后端 `VoiceSessionController` 用来签发通话方 token 的是同一套 LiveKit 项目凭证 |
| `ELEVEN_API_KEY` | 是 | 被 `livekit-plugins-elevenlabs` 的 STT/TTS 类自动读取（注意是 `ELEVEN_`，不是 `ELEVENLABS_`） |
| `AGENT_RUNTIME_BASE_URL` | 否（默认 `http://localhost:8080`） | Java `agent-runtime-api` 的基础地址 |
| `SPATIUS_AVATAR_ID` | 否 | 设置了的话，Agent 的语音会经过 Spatius 头像渲染，而不是直接发布原始音频 |
| `SPATIUS_API_KEY` / `SPATIUS_APP_ID` | 只有设了 `SPATIUS_AVATAR_ID` 才需要 | 被 `livekit-plugins-spatius` 的 `AvatarSession` 自动读取 |
| `SPATIUS_REGION` | 否（默认 `auto`） | 固定 Spatius 的渲染/接入区域，而不是让它的 bootstrap API 自动选。见下文"头像画面是黑屏"。 |

## 排查记录：头像视频画面是黑屏（音频正常）

测试中已经通过浏览器端的 WebRTC 统计数据（`RTCPeerConnection.getStats()`）确认过：音频播放完全正常，但视频的 `inbound-rtp` 报告显示 `framesDecoded: 0`，且 `framesDropped` 等于 `framesReceived` —— 每一个视频包都收到了，但没有一帧被成功解码（播放端一直在发 PLI 请求，要求重发一个完整的关键帧，但始终没等到）。这**不是**浏览器自动播放拦截问题，也不是 `static/index.html` 前端代码能修的 —— 视频码流在到达浏览器之前就已经是坏的了。

Spatius 的 `auto` 区域解析在测试中选到了 `cn-beijing`，而 LiveKit Cloud 房间跑在东京（`Japan A`）—— 一条不匹配/过长的网络路径看起来是个说得通的原因，所以试过：

```bash
SPATIUS_REGION=us-west LIVEKIT_URL=... python3 worker.py dev
```

**试过了，没有解决。** 用同样的方法重新抓 `getStats()`，固定成 `us-west` 之后失败模式完全一样（`framesDecoded: 0`，100% 丢帧）—— 甚至每包的字节数比例还更差。这排除了区域/路由是根因的可能，指向 Spatius 编码流水线本身的系统性问题（或者是账号/avatar_id 配置问题），不是这个仓库能修的。需要带着上面这些 `getStats()` 数据（视频 `inbound-rtp` 报告里的 `framesReceived`/`framesDecoded`/`framesDropped`/`pliCount`/`bytesReceived`）以及 `avatar_id`/`app_id` 去找 Spatius 技术支持。

## 不用真麦克风做人工验证（`test_call.py`）

一个一次性的诊断脚本：以通话方身份连进一个 LiveKit 房间（传入一份 `{roomName, livekitUrl, livekitToken}` 的 JSON，比如 `POST /api/v1/voice-sessions` 的返回值），把一段预先录好的 WAV 当作假麦克风轨道发布出去，并打印房间/参与者/轨道相关的事件 —— 这样整条链路（worker 分配、STT、后端往返、TTS、头像加入）都能不靠真人、不靠真麦克风端到端跑一遍：

```bash
say "what time is it" -o /tmp/test_speech.aiff  # macOS；其他系统用任意 TTS/录音方式即可
afconvert -f WAVE -d LEI16@16000 -c 1 /tmp/test_speech.aiff /tmp/test_speech.wav
python3 test_call.py /tmp/voice_session.json /tmp/test_speech.wav
```

上面那个头像黑屏问题，实际上就是靠这个脚本（配合一段拦截 `RTCPeerConnection` 来抓 `getStats()` 的 Playwright 脚本）诊断出来的 —— 那个 Playwright 脚本没有收进这个仓库，因为它是一次性的诊断工具，不是 worker 本身的一部分。

## 本地运行

1. 确认 `agent-runtime-api`（Java 后端）已经在跑，并且能通过 `AGENT_RUNTIME_BASE_URL` 访问到。
2. 以开发模式启动 worker（自动重连、详细日志，连上 LiveKit 后等待房间分配）：

   ```bash
   LIVEKIT_URL=wss://... LIVEKIT_API_KEY=... LIVEKIT_API_SECRET=... \
   ELEVEN_API_KEY=... \
   python3 worker.py dev
   ```

3. 触发一次通话：在 Java 后端调 `POST /api/v1/voice-sessions {"agentKey": "general-assistant"}`，拿到 `{roomName, livekitToken, livekitUrl, conversationId}`。用这个 token/URL 连上任意 LiveKit 客户端（比如 [Agents Playground](https://agents-playground.livekit.io/)）—— 一旦有参与者加入房间，LiveKit 会自动把这个 worker 分配进去。

## 关于 API 稳定性的说明

`backend_llm.py` 和 `worker.py` 里用到的 `livekit-agents` / `livekit-plugins-elevenlabs` / `livekit-plugins-spatius` 的确切类和方法签名，都是在写这份代码的当时，针对 `pyproject.toml` 里锁定的版本（`livekit-agents` 1.7.1、`livekit-plugins-spatius` 1.7.1）用 `python3 -c "import inspect; ..."` 逐个核实过的。这些包迭代很快 —— 如果 `pip install -e .` 装到了更新的版本导致哪里坏了，请用同样的方式重新核实相关签名，不要凭文档猜（文档通常滞后于发布）。
