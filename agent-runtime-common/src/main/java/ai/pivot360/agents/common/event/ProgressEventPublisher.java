package ai.pivot360.agents.common.event;

/**
 * Dependency-inverted publish port: the temporal module depends only on this
 * abstraction, while the concrete Redis-backed implementation lives in the api
 * module (which sits above temporal in the dependency graph).
 */
public interface ProgressEventPublisher {

    void publish(ProgressEvent event);
}
