package ai.pivot360.agents.common.tenant;

/**
 * Holds the current tenant id for the duration of a single HTTP request thread.
 *
 * <p>This is intentionally NOT used beyond the originating request thread: Temporal
 * workflow/activity execution happens on pooled threads shared across tenants (and
 * potentially in a separate JVM), where a ThreadLocal would either be empty or, worse,
 * leak a previous tenant's id. Everywhere below the HTTP boundary, tenantId must be an
 * explicit method/field parameter instead of being re-read from here.
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static String get() {
        return CURRENT_TENANT.get();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
