package ai.pivot360.agents.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot 4 defaults to Jackson 3's immutable {@code tools.jackson.databind.json.JsonMapper}
 * and does not auto-configure a {@code com.fasterxml.jackson.databind.ObjectMapper} bean.
 * We still use Jackson 2's ObjectMapper explicitly (progress-event serialization, run
 * input/result payloads), so it's wired here rather than migrated to Jackson 3 for now.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
