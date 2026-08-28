package ai.pivot360.agents.api.mcp;

import ai.pivot360.agents.api.service.AgentRunService;
import ai.pivot360.agents.api.service.StartRunResult;
import ai.pivot360.agents.persistence.entity.AgentRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * MCP server role: exposes this platform's own agents as MCP tools so external
 * systems can invoke them over the MCP protocol. Delegates to the same
 * {@link AgentRunService} the REST controller uses -- one orchestration entry point,
 * two transports.
 */
@Component
public class AgentInvocationTools {

    private final AgentRunService agentRunService;
    private final ObjectMapper objectMapper;

    public AgentInvocationTools(AgentRunService agentRunService, ObjectMapper objectMapper) {
        this.agentRunService = agentRunService;
        this.objectMapper = objectMapper;
    }

    @McpTool(name = "invoke_agent", description = "Start a run of a registered agent and return immediately with its run id")
    public Map<String, Object> invokeAgent(
            @McpToolParam(description = "Tenant identifier", required = true) String tenantId,
            @McpToolParam(description = "Registered agent key, e.g. general-assistant", required = true) String agentKey,
            @McpToolParam(description = "The task/question to give the agent", required = true) String taskInput,
            @McpToolParam(description = "Omit to start a new conversation; pass a prior conversationId to continue it", required = false) String conversationId) {
        UUID conversationUuid = conversationId == null || conversationId.isBlank() ? null : UUID.fromString(conversationId);
        StartRunResult result = agentRunService.startRun(tenantId, agentKey, taskInput, conversationUuid);
        return Map.of("runId", result.agentRunId().toString(), "conversationId", result.conversationId().toString(), "status", "PENDING");
    }

    @McpTool(name = "get_agent_run_result", description = "Get the current status and result of a previously started agent run")
    public Map<String, Object> getAgentRunResult(
            @McpToolParam(description = "Tenant identifier", required = true) String tenantId,
            @McpToolParam(description = "Run id returned by invoke_agent", required = true) String runId) {
        AgentRun run = agentRunService.getRun(tenantId, UUID.fromString(runId));
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("status", run.getStatus().name());
        result.put("result", readResult(run.getResultPayload()));
        result.put("errorMessage", run.getErrorMessage());
        return result;
    }

    /** result_payload is stored as a jsonb-encoded string; unwrap it back to plain text for MCP callers. */
    private String readResult(String resultPayloadJson) {
        if (resultPayloadJson == null) {
            return null;
        }
        try {
            return objectMapper.readValue(resultPayloadJson, String.class);
        } catch (Exception e) {
            return resultPayloadJson;
        }
    }
}
