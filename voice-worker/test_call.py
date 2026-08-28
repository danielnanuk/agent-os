"""One-shot manual verification script (not part of the shipped worker): connects to a
LiveKit room as the "caller", publishes a pre-recorded WAV as a fake microphone, and
logs room/participant/track events so we can observe the worker dispatching, ElevenLabs
STT/TTS running, and the Spatius avatar joining -- without a real human or microphone.
"""

import asyncio
import json
import sys
import wave

from livekit import rtc


async def publish_wav(room: rtc.Room, wav_path: str) -> None:
    with wave.open(wav_path, "rb") as wf:
        sample_rate = wf.getframerate()
        num_channels = wf.getnchannels()
        frames = wf.readframes(wf.getnframes())

    source = rtc.AudioSource(sample_rate, num_channels)
    track = rtc.LocalAudioTrack.create_audio_track("test-mic", source)
    await room.local_participant.publish_track(track, rtc.TrackPublishOptions(source=rtc.TrackSource.SOURCE_MICROPHONE))
    print("published fake microphone track", flush=True)

    samples_per_channel = sample_rate // 100  # 10ms frames
    bytes_per_frame = samples_per_channel * num_channels * 2  # 16-bit PCM
    offset = 0
    while offset < len(frames):
        chunk = frames[offset:offset + bytes_per_frame]
        offset += bytes_per_frame
        if len(chunk) < bytes_per_frame:
            chunk = chunk + b"\x00" * (bytes_per_frame - len(chunk))
        frame = rtc.AudioFrame(chunk, sample_rate, num_channels, samples_per_channel)
        await source.capture_frame(frame)
        await asyncio.sleep(0.01)
    print("finished publishing wav", flush=True)


async def main(url: str, token: str, wav_path: str) -> None:
    room = rtc.Room()

    @room.on("participant_connected")
    def on_participant_connected(participant: rtc.RemoteParticipant) -> None:
        print(f"[event] participant_connected: identity={participant.identity} metadata={participant.metadata!r}", flush=True)

    @room.on("track_subscribed")
    def on_track_subscribed(track: rtc.Track, publication: rtc.RemoteTrackPublication, participant: rtc.RemoteParticipant) -> None:
        print(f"[event] track_subscribed: kind={track.kind} from={participant.identity}", flush=True)

    @room.on("track_published")
    def on_track_published(publication: rtc.RemoteTrackPublication, participant: rtc.RemoteParticipant) -> None:
        print(f"[event] track_published: kind={publication.kind} source={publication.source} from={participant.identity}", flush=True)

    await room.connect(url, token)
    print(f"connected to room={room.name}", flush=True)

    # Give the dispatched worker time to finish initializing (VAD model load, avatar
    # handshake, RoomIO track subscription) before we start "speaking" -- audio
    # published before RoomIO subscribes is simply never delivered, not buffered.
    print("waiting 10s for the worker's audio pipeline to finish initializing...", flush=True)
    await asyncio.sleep(10)

    await publish_wav(room, wav_path)

    print("listening for 25s to observe worker dispatch / avatar join / reply audio...", flush=True)
    await asyncio.sleep(25)

    print("remote participants at end:", flush=True)
    for identity, p in room.remote_participants.items():
        print(f"  - {identity} metadata={p.metadata!r} tracks={[pub.kind for pub in p.track_publications.values()]}", flush=True)

    await room.disconnect()


if __name__ == "__main__":
    session = json.load(open(sys.argv[1]))
    asyncio.run(main(session["livekitUrl"], session["livekitToken"], sys.argv[2]))
