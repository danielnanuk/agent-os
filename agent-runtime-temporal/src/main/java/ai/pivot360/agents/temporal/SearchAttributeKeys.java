package ai.pivot360.agents.temporal;

import io.temporal.common.SearchAttributeKey;

/**
 * Custom search attributes registered once against the Temporal cluster (see the
 * {@code temporal-setup-search-attributes} one-shot container in docker-compose.yml)
 * before any workflow using them is started.
 */
public final class SearchAttributeKeys {

    public static final SearchAttributeKey<String> TENANT_ID = SearchAttributeKey.forKeyword("TenantId");
    public static final SearchAttributeKey<String> AGENT_KEY = SearchAttributeKey.forKeyword("AgentKey");

    private SearchAttributeKeys() {
    }
}
