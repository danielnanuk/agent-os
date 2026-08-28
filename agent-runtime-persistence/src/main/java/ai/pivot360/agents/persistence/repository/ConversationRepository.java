package ai.pivot360.agents.persistence.repository;

import ai.pivot360.agents.persistence.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    Optional<Conversation> findByIdAndTenantId(UUID id, String tenantId);
}
