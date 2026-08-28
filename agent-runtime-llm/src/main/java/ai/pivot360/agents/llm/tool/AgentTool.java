package ai.pivot360.agents.llm.tool;

/**
 * Marker interface for Spring beans exposing one or more {@code @Tool}-annotated
 * methods, so {@link ToolCallbackRegistry} can collect exactly these beans via
 * {@code List<AgentTool>} injection instead of grabbing every bean in the context.
 */
public interface AgentTool {
}
