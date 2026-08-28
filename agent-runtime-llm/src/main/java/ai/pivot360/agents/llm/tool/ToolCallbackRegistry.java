package ai.pivot360.agents.llm.tool;

import ai.pivot360.agents.common.exception.AgentRuntimeException;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Single source of truth mapping a tool name (as stored in
 * {@code agent_definitions.tool_names}) to its {@link ToolCallback}.
 *
 * <p>The same {@link ToolCallback} is used for two distinct purposes at two distinct
 * points in the ReAct loop: {@code LlmActivities} reads its {@link ToolCallback#getToolDefinition()}
 * to advertise the schema to the model, while {@code ToolActivities} calls
 * {@link ToolCallback#call(String)} to actually execute it — each in its own Temporal
 * Activity, so Spring AI itself never auto-executes a tool.
 */
@Component
public class ToolCallbackRegistry {

    private final Map<String, ToolCallback> callbacksByName;

    public ToolCallbackRegistry(List<AgentTool> toolBeans) {
        this.callbacksByName = toolBeans.stream()
                .flatMap(bean -> java.util.Arrays.stream(ToolCallbacks.from(bean)))
                .collect(Collectors.toMap(cb -> cb.getToolDefinition().name(), cb -> cb));
    }

    public ToolCallback get(String toolName) {
        ToolCallback callback = callbacksByName.get(toolName);
        if (callback == null) {
            throw new AgentRuntimeException("No tool registered with name " + toolName);
        }
        return callback;
    }

    public List<ToolCallback> get(List<String> toolNames) {
        return toolNames.stream().map(this::get).collect(Collectors.toList());
    }
}
