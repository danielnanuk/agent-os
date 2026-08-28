package ai.pivot360.agents.common.activity;

/** A single tool-call intent requested by the LLM, before it has been executed. */
public record ToolCallDto(
        String id,
        String toolName,
        String argumentsJson
) {
}
