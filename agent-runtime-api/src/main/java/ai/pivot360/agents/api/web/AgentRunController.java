package ai.pivot360.agents.api.web;

import ai.pivot360.agents.api.service.AgentRunService;
import ai.pivot360.agents.api.service.StartRunResult;
import ai.pivot360.agents.api.streaming.RedisSubscriptionBridge;
import ai.pivot360.agents.api.streaming.SseSubscriptionManager;
import ai.pivot360.agents.common.agent.AgentRunStatus;
import ai.pivot360.agents.persistence.entity.AgentRun;
import ai.pivot360.agents.persistence.entity.ConversationMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agent-runs")
public class AgentRunController {

    private final AgentRunService agentRunService;
    private final SseSubscriptionManager sseSubscriptionManager;
    private final RedisSubscriptionBridge redisSubscriptionBridge;
    private final ObjectMapper objectMapper;

    public AgentRunController(AgentRunService agentRunService,
                               SseSubscriptionManager sseSubscriptionManager,
                               RedisSubscriptionBridge redisSubscriptionBridge,
                               ObjectMapper objectMapper) {
        this.agentRunService = agentRunService;
        this.sseSubscriptionManager = sseSubscriptionManager;
        this.redisSubscriptionBridge = redisSubscriptionBridge;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> submit(@TenantId String tenantId,
                                                        @Valid @RequestBody SubmitAgentRunRequest request) {
        StartRunResult result = agentRunService.startRun(
                tenantId, request.agentKey(), request.input(), request.conversationId());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("runId", result.agentRunId(), "conversationId", result.conversationId(), "status", "PENDING"));
    }

    @GetMapping("/{runId}")
    public AgentRunView get(@TenantId String tenantId, @PathVariable UUID runId) {
        AgentRun run = agentRunService.getRun(tenantId, runId);
        return new AgentRunView(run.getId(), run.getStatus(), readResult(run.getResultPayload()), run.getErrorMessage());
    }

    /** result_payload is stored as a jsonb-encoded string; unwrap it back to plain text for the API response. */
    private String readResult(String resultPayloadJson) {
        if (resultPayloadJson == null) {
            return null;
        }
        try {
            return objectMapper.readValue(resultPayloadJson, String.class);
        } catch (Exception e) {
            return resultPayloadJson;
        }
    }

    @GetMapping(value = "/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@TenantId String tenantId, @PathVariable UUID runId) {
        AgentRun run = agentRunService.getRun(tenantId, runId);
        SseEmitter emitter = new SseEmitter(0L);

        for (ConversationMessage message : agentRunService.getMessages(tenantId, runId)) {
            sendSafely(emitter, "history", message);
        }
        sendSafely(emitter, "status", Map.of("status", run.getStatus()));

        if (isTerminal(run.getStatus())) {
            // Already finished (e.g. a reconnect after the run ended) -- no more progress
            // events will ever be published for this run, so don't leave the connection
            // open waiting for one.
            emitter.complete();
            return emitter;
        }

        sseSubscriptionManager.register(runId, emitter);
        redisSubscriptionBridge.subscribe(tenantId, runId);

        Runnable cleanup = () -> {
            sseSubscriptionManager.unregister(runId, emitter);
            if (!sseSubscriptionManager.hasSubscribers(runId)) {
                redisSubscriptionBridge.unsubscribe(tenantId, runId);
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        return emitter;
    }

    private boolean isTerminal(AgentRunStatus status) {
        return status == AgentRunStatus.COMPLETED || status == AgentRunStatus.FAILED || status == AgentRunStatus.CANCELLED;
    }

    private void sendSafely(SseEmitter emitter, String eventName, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(objectMapper.writeValueAsString(payload)));
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }
}
