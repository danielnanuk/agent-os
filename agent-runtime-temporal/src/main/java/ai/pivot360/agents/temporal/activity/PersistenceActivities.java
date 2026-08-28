package ai.pivot360.agents.temporal.activity;

import ai.pivot360.agents.common.activity.AppendMessageRequest;
import ai.pivot360.agents.common.activity.SaveRunResultRequest;
import ai.pivot360.agents.common.activity.UpdateRunStatusRequest;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface PersistenceActivities {

    @ActivityMethod
    void appendMessage(AppendMessageRequest request);

    @ActivityMethod
    void updateRunStatus(UpdateRunStatusRequest request);

    @ActivityMethod
    void saveRunResult(SaveRunResultRequest request);
}
