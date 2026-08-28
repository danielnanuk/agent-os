package ai.pivot360.agents.persistence.repository;

import ai.pivot360.agents.persistence.entity.ConversationMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, UUID> {

    List<ConversationMessage> findByAgentRunIdAndTenantIdOrderBySequenceNumberAsc(UUID agentRunId, String tenantId);

    /** Cross-run history for a conversation's prior turns, in the order they actually happened. */
    List<ConversationMessage> findByAgentRunIdInAndTenantIdOrderByCreatedAtAsc(List<UUID> agentRunIds, String tenantId);

    int countByAgentRunIdAndTenantId(UUID agentRunId, String tenantId);
}
