package ai.pivot360.agents.common.event;

public enum ProgressEventType {
    RUN_STARTED,
    LLM_DELTA,
    LLM_THOUGHT,
    TOOL_CALL_STARTED,
    TOOL_CALL_COMPLETED,
    WAITING_FOR_APPROVAL,
    RUN_COMPLETED,
    RUN_FAILED
}
