package ai.pivot360.agents.temporal.activity.impl;

import ai.pivot360.agents.common.event.ProgressEvent;
import ai.pivot360.agents.common.event.ProgressEventPublisher;
import ai.pivot360.agents.temporal.TemporalQueues;
import ai.pivot360.agents.temporal.activity.StreamingActivities;
import io.temporal.spring.boot.ActivityImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Best-effort: a Redis blip must never fail the whole agent run, since the durable
 * source of truth is Postgres (conversation_messages / agent_runs) and streaming is
 * a UX layer on top of it. Failures are logged, not propagated.
 */
@Component
@ActivityImpl(taskQueues = TemporalQueues.AGENT_RUNTIME_DEFAULT)
public class StreamingActivitiesImpl implements StreamingActivities {

    private static final Logger log = LoggerFactory.getLogger(StreamingActivitiesImpl.class);

    private final ProgressEventPublisher publisher;

    public StreamingActivitiesImpl(ProgressEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void publishProgressEvent(ProgressEvent event) {
        try {
            publisher.publish(event);
        } catch (RuntimeException e) {
            log.warn("Failed to publish progress event for run {}: {}", event.agentRunId(), e.getMessage());
        }
    }
}
