package ai.pivot360.agents.temporal.activity;

import ai.pivot360.agents.common.activity.ToolInvocationRequest;
import ai.pivot360.agents.common.activity.ToolResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface ToolActivities {

    @ActivityMethod
    ToolResult invokeTool(ToolInvocationRequest request);
}
