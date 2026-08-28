package ai.pivot360.agents.persistence.repository;

import ai.pivot360.agents.persistence.entity.AgentRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentRunRepository extends JpaRepository<AgentRun, UUID> {

    Optional<AgentRun> findByIdAndTenantId(UUID id, String tenantId);

    Optional<AgentRun> findByWorkflowIdAndTenantId(String workflowId, String tenantId);

    List<AgentRun> findByConversationIdAndTenantIdOrderByCreatedAtAsc(UUID conversationId, String tenantId);
}
