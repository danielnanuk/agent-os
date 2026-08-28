package ai.pivot360.agents.api.web;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record SubmitAgentRunRequest(
        @NotBlank String agentKey,
        @NotBlank String input,
        /** Omit to start a new conversation; pass a prior response's conversationId to continue it. */
        UUID conversationId
) {
}
