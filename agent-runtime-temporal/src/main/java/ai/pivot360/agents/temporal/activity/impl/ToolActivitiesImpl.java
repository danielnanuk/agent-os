package ai.pivot360.agents.temporal.activity.impl;

import ai.pivot360.agents.common.activity.ToolInvocationRequest;
import ai.pivot360.agents.common.activity.ToolResult;
import ai.pivot360.agents.temporal.TemporalQueues;
import ai.pivot360.agents.temporal.activity.ToolActivities;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

@Component
@ActivityImpl(taskQueues = TemporalQueues.AGENT_RUNTIME_DEFAULT)
public class ToolActivitiesImpl implements ToolActivities {

    private final CompositeToolResolver toolResolver;

    public ToolActivitiesImpl(CompositeToolResolver toolResolver) {
        this.toolResolver = toolResolver;
    }

    @Override
    public ToolResult invokeTool(ToolInvocationRequest request) {
        String toolName = request.toolCall().toolName();
        try {
            ToolCallback callback = toolResolver.resolve(toolName);
            String result = callback.call(request.toolCall().argumentsJson());
            return new ToolResult(request.toolCall().id(), toolName, result, true, null);
        } catch (RuntimeException e) {
            return new ToolResult(request.toolCall().id(), toolName, null, false, e.getMessage());
        }
    }
}
