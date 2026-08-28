package ai.pivot360.agents.api.streaming;

import ai.pivot360.agents.common.event.ProgressEvent;
import ai.pivot360.agents.common.event.ProgressEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Tracks only the SSE connections held by *this* JVM instance. */
@Component
public class SseSubscriptionManager {

    private static final Logger log = LoggerFactory.getLogger(SseSubscriptionManager.class);

    private final Map<UUID, List<SseEmitter>> emittersByRun = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public SseSubscriptionManager(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void register(UUID runId, SseEmitter emitter) {
        emittersByRun.computeIfAbsent(runId, id -> new CopyOnWriteArrayList<>()).add(emitter);
    }

    public void unregister(UUID runId, SseEmitter emitter) {
        emittersByRun.computeIfPresent(runId, (id, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }

    public boolean hasSubscribers(UUID runId) {
        return emittersByRun.containsKey(runId);
    }

    /**
     * Sends the event to every emitter for this run, then closes them if it's a terminal
     * progress event -- otherwise the SseEmitter (created with an infinite timeout so it
     * can wait indefinitely for the next event) would stay open forever after the run is
     * done, leaking the connection, the Redis listener, and this map entry.
     */
    public void emit(UUID runId, String eventName, String data) {
        List<SseEmitter> emitters = emittersByRun.get(runId);
        if (emitters == null) {
            return;
        }
        boolean terminal = isTerminal(data);
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
                if (terminal) {
                    emitter.complete();
                }
            } catch (IOException e) {
                log.debug("SSE emitter for run {} failed, completing with error", runId, e);
                emitter.completeWithError(e);
            }
        }
        if (terminal) {
            emittersByRun.remove(runId);
        }
    }

    private boolean isTerminal(String progressEventJson) {
        try {
            ProgressEventType type = objectMapper.readValue(progressEventJson, ProgressEvent.class).eventType();
            return type == ProgressEventType.RUN_COMPLETED || type == ProgressEventType.RUN_FAILED;
        } catch (Exception e) {
            return false;
        }
    }
}
