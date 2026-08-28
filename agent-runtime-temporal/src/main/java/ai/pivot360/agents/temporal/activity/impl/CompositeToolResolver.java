package ai.pivot360.agents.temporal.activity.impl;

import ai.pivot360.agents.llm.tool.ToolCallbackRegistry;
import ai.pivot360.agents.mcp.client.McpToolCallbackRegistry;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resolves a tool by name against the local (Spring {@code @Tool}-annotated) registry
 * first, falling back to whatever the MCP client has discovered from external servers.
 * Used by both {@code LlmActivitiesImpl} (schema advertisement) and
 * {@code ToolActivitiesImpl} (actual execution), so both sides agree on the same set
 * of callable tools regardless of where a tool is implemented.
 */
@Component
public class CompositeToolResolver {

    private final ToolCallbackRegistry localTools;
    private final McpToolCallbackRegistry mcpTools;

    public CompositeToolResolver(ToolCallbackRegistry localTools, McpToolCallbackRegistry mcpTools) {
        this.localTools = localTools;
        this.mcpTools = mcpTools;
    }

    public ToolCallback resolve(String toolName) {
        return mcpTools.find(toolName).orElseGet(() -> localTools.get(toolName));
    }

    public List<ToolCallback> resolve(List<String> toolNames) {
        return toolNames.stream().map(this::resolve).toList();
    }
}
