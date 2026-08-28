package ai.pivot360.agents.common.activity;

public record ToolResult(
        String toolCallId,
        String toolName,
        String resultContent,
        boolean success,
        String errorMessage
) {
}
