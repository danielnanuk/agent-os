package ai.pivot360.agents.persistence.repository;

import ai.pivot360.agents.persistence.entity.AgentDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AgentDefinitionRepository extends JpaRepository<AgentDefinition, UUID> {

    /**
     * Resolves a tenant-private definition first, falling back to the global
     * (tenant_id IS NULL) definition with the same key.
     */
    @Query("""
            SELECT d FROM AgentDefinition d
            WHERE d.agentKey = :agentKey
              AND (d.tenantId = :tenantId OR d.tenantId IS NULL)
            ORDER BY CASE WHEN d.tenantId IS NULL THEN 1 ELSE 0 END ASC
            LIMIT 1
            """)
    Optional<AgentDefinition> findForTenant(@Param("tenantId") String tenantId, @Param("agentKey") String agentKey);
}
