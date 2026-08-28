package ai.pivot360.agents.temporal.activity;

import ai.pivot360.agents.common.event.ProgressEvent;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface StreamingActivities {

    @ActivityMethod
    void publishProgressEvent(ProgressEvent event);
}
