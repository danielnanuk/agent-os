package ai.pivot360.agents;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Deliberately placed directly under the {@code ai.pivot360.agents} root package
 * (rather than {@code ai.pivot360.agents.api}) so Spring Boot's default component
 * scan covers every module's sub-package (.common, .persistence, .llm, .mcp,
 * .temporal, .api) without needing explicit @ComponentScan/@EntityScan/
 * @EnableJpaRepositories base-package overrides.
 */
@SpringBootApplication
public class AgentRuntimeApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentRuntimeApplication.class, args);
    }
}
