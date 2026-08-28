package ai.pivot360.agents.common.activity;

import java.util.List;

public record LlmResponse(
        String assistantContent,
        List<ToolCallDto> toolCalls
) {
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
