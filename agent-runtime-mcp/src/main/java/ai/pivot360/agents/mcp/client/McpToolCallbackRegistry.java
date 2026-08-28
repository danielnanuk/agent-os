package ai.pivot360.agents.mcp.client;

import ai.pivot360.agents.common.exception.AgentRuntimeException;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Name-keyed view over whatever {@link ToolCallback}s the auto-configured MCP client
 * (spring-ai-starter-mcp-client) has discovered from connected external MCP servers.
 *
 * <p>{@code SyncMcpToolCallbackProvider} is only auto-configured when at least one MCP
 * server connection is present in configuration, so it's injected optionally: a
 * runtime with no external MCP servers configured simply resolves an empty map here.
 */
@Component
public class McpToolCallbackRegistry {

    private final Map<String, ToolCallback> callbacksByName;

    public McpToolCallbackRegistry(ObjectProvider<SyncMcpToolCallbackProvider> toolCallbackProvider) {
        this.callbacksByName = toolCallbackProvider.stream()
                .flatMap(provider -> java.util.Arrays.stream(provider.getToolCallbacks()))
                .collect(Collectors.toMap(cb -> cb.getToolDefinition().name(), cb -> cb));
    }

    public Optional<ToolCallback> find(String toolName) {
        return Optional.ofNullable(callbacksByName.get(toolName));
    }

    public ToolCallback get(String toolName) {
        return find(toolName)
                .orElseThrow(() -> new AgentRuntimeException("No MCP tool registered with name " + toolName));
    }
}
