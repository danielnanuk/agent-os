package ai.pivot360.agents.api.web;

import jakarta.validation.constraints.NotBlank;

public record CreateVoiceSessionRequest(@NotBlank String agentKey) {
}
