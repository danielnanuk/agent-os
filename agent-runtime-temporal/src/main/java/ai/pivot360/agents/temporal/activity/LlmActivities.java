package ai.pivot360.agents.temporal.activity;

import ai.pivot360.agents.common.activity.LlmCallRequest;
import ai.pivot360.agents.common.activity.LlmResponse;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface LlmActivities {

    @ActivityMethod
    LlmResponse callChatModel(LlmCallRequest request);
}
