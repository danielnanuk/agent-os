package ai.pivot360.agents.api.web;

import ai.pivot360.agents.api.service.TenantService;
import ai.pivot360.agents.common.exception.AgentRuntimeException;
import ai.pivot360.agents.common.exception.NotFoundException;
import ai.pivot360.agents.persistence.entity.AgentDefinition;
import ai.pivot360.agents.persistence.entity.Conversation;
import ai.pivot360.agents.persistence.repository.AgentDefinitionRepository;
import ai.pivot360.agents.persistence.repository.ConversationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.livekit.server.AccessToken;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bootstraps a realtime voice call: creates a {@link Conversation} (so the voice turns
 * share history the same way a multi-turn text conversation does) and issues a LiveKit
 * room access token. The Python voice-worker joining that room reads {@code tenantId}/
 * {@code agentKey}/{@code conversationId} back out of the token's metadata -- nothing
 * about the actual voice/media pipeline happens here, only session bootstrap.
 */
@RestController
@RequestMapping("/api/v1/voice-sessions")
public class VoiceSessionController {

    private final AgentDefinitionRepository agentDefinitionRepository;
    private final ConversationRepository conversationRepository;
    private final TenantService tenantService;
    private final ObjectMapper objectMapper;
    private final String livekitApiKey;
    private final String livekitApiSecret;
    private final String livekitUrl;

    public VoiceSessionController(AgentDefinitionRepository agentDefinitionRepository,
                                   ConversationRepository conversationRepository,
                                   TenantService tenantService,
                                   ObjectMapper objectMapper,
                                   @Value("${livekit.api-key:}") String livekitApiKey,
                                   @Value("${livekit.api-secret:}") String livekitApiSecret,
                                   @Value("${livekit.url:}") String livekitUrl) {
        this.agentDefinitionRepository = agentDefinitionRepository;
        this.conversationRepository = conversationRepository;
        this.tenantService = tenantService;
        this.objectMapper = objectMapper;
        this.livekitApiKey = livekitApiKey;
        this.livekitApiSecret = livekitApiSecret;
        this.livekitUrl = livekitUrl;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createSession(@TenantId String tenantId,
                                                               @Valid @RequestBody CreateVoiceSessionRequest request) {
        if (livekitApiKey.isBlank() || livekitApiSecret.isBlank()) {
            throw new AgentRuntimeException("Voice is not configured (missing LIVEKIT_API_KEY/LIVEKIT_API_SECRET)");
        }

        tenantService.getOrCreate(tenantId);
        AgentDefinition definition = agentDefinitionRepository.findForTenant(tenantId, request.agentKey())
                .orElseThrow(() -> new NotFoundException("No agent definition found for key: " + request.agentKey()));

        Conversation conversation = conversationRepository.save(new Conversation(tenantId));
        String roomName = "voice-" + conversation.getId();

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("tenantId", tenantId);
        metadata.put("agentKey", definition.getAgentKey());
        metadata.put("conversationId", conversation.getId().toString());

        AccessToken token = new AccessToken(livekitApiKey, livekitApiSecret);
        token.setIdentity("caller-" + UUID.randomUUID());
        token.setMetadata(writeJson(metadata));
        token.addGrants(new RoomJoin(true), new RoomName(roomName));

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "roomName", roomName,
                "livekitUrl", livekitUrl,
                "livekitToken", token.toJwt(),
                "conversationId", conversation.getId()));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new AgentRuntimeException("Failed to serialize voice session metadata", e);
        }
    }
}
