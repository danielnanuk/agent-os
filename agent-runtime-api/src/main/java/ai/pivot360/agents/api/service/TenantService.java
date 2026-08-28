package ai.pivot360.agents.api.service;

import ai.pivot360.agents.persistence.entity.Tenant;
import ai.pivot360.agents.persistence.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Auto-provisions a {@link Tenant} row on first request for a given tenantKey, so
 * agent_runs/agent_definitions have a real FK-able referent without requiring a
 * separate "create tenant" admin step before the API can be exercised.
 */
@Service
public class TenantService {

    private final TenantRepository tenantRepository;

    public TenantService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Transactional
    public Tenant getOrCreate(String tenantKey) {
        return tenantRepository.findByTenantKey(tenantKey)
                .orElseGet(() -> tenantRepository.save(new Tenant(tenantKey)));
    }
}
