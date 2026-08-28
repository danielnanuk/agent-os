package ai.pivot360.agents.persistence.repository;

import ai.pivot360.agents.persistence.entity.LlmModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LlmModelRepository extends JpaRepository<LlmModel, UUID> {
}
