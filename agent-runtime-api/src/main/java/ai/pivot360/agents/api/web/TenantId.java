package ai.pivot360.agents.api.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method parameter to be resolved from {@link TenantContextFilter}'s
 * validated header value, making the tenantId dependency visible in the method
 * signature instead of controllers reaching into {@code TenantContext} themselves.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface TenantId {
}
