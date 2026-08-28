package ai.pivot360.agents.api.web;

import ai.pivot360.agents.common.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Requires an {@code X-Tenant-Id} header on every request and makes it available to
 * the current request thread via {@link TenantContext}.
 *
 * <p>This is explicitly NOT authentication -- it trusts the raw header value as-is.
 * A future auth layer should run before this filter in the chain and set a verified
 * tenant claim (e.g. from a JWT) instead of a client-supplied header; nothing below
 * this point needs to change since everything downstream already takes tenantId as an
 * explicit parameter rather than re-reading TenantContext.
 */
@Component
public class TenantContextFilter extends OncePerRequestFilter {

    public static final String TENANT_HEADER = "X-Tenant-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/")) {
            // Static assets (the demo chat page, etc.) aren't tenant-scoped -- only the API is.
            chain.doFilter(request, response);
            return;
        }
        String tenantId = request.getHeader(TENANT_HEADER);
        if (tenantId == null || tenantId.isBlank()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing required header: " + TENANT_HEADER);
            return;
        }
        try {
            TenantContext.set(tenantId);
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
