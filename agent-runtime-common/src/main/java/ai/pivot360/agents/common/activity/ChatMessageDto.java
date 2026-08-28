package ai.pivot360.agents.common.activity;

import ai.pivot360.agents.common.agent.MessageRole;

import java.util.List;

/**
 * Plain, Jackson-friendly representation of one conversation message. Deliberately
 * not a Spring AI {@code Message} type: Temporal activity payloads must stay stable,
 * simple POJOs independent of the LLM library's internal object model.
 *
 * <p>{@code toolCalls} is only populated for an ASSISTANT message that requested tool
 * calls (as opposed to a final text answer); it must be echoed back on the next LLM
 * call so the model sees the original request its subsequent TOOL response messages
 * are replying to.
 */
public record ChatMessageDto(
        MessageRole role,
        String content,
        String toolName,
        String toolCallId,
        List<ToolCallDto> toolCalls
) {
    public static ChatMessageDto user(String content) {
        return new ChatMessageDto(MessageRole.USER, content, null, null, null);
    }

    public static ChatMessageDto assistant(String content, List<ToolCallDto> toolCalls) {
        return new ChatMessageDto(MessageRole.ASSISTANT, content, null, null, toolCalls);
    }

    public static ChatMessageDto tool(String toolName, String toolCallId, String content) {
        return new ChatMessageDto(MessageRole.TOOL, content, toolName, toolCallId, null);
    }
}
