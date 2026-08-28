package ai.pivot360.agents.llm.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** Web search via Tavily (https://tavily.com), an API purpose-built for feeding LLM agents. */
@Component
public class WebSearchTools implements AgentTool {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public WebSearchTools(@Value("${tavily.api-key:}") String apiKey,
                           @Value("${tavily.base-url:https://api.tavily.com}") String baseUrl,
                           ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.restClient = RestClient.create(baseUrl);
        this.objectMapper = objectMapper;
    }

    @Tool(description = "Search the web for up-to-date information. Returns a short list of relevant "
            + "results with titles, URLs, and snippets.")
    public String web_search(@ToolParam(description = "The search query") String query) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Web search is not configured (missing TAVILY_API_KEY)");
        }

        // Deserialize via our own Jackson 2 ObjectMapper rather than RestClient's default
        // message converters -- Spring Boot 4 auto-configures those for Jackson 3
        // (tools.jackson.*), which can't produce a Jackson 2 JsonNode.
        String rawJson = restClient.post()
                .uri("/search")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKey)
                .body(Map.of("query", query, "search_depth", "basic", "max_results", 5))
                .retrieve()
                .body(String.class);

        JsonNode response;
        try {
            response = objectMapper.readTree(rawJson);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Tavily response", e);
        }

        return formatResults(response);
    }

    private String formatResults(JsonNode response) {
        StringBuilder sb = new StringBuilder();

        JsonNode answer = response.path("answer");
        if (answer.isTextual() && !answer.asText().isBlank()) {
            sb.append("Answer: ").append(answer.asText()).append("\n\n");
        }

        JsonNode results = response.path("results");
        int i = 1;
        for (JsonNode result : results) {
            sb.append(i++).append(". ").append(result.path("title").asText())
                    .append(" (").append(result.path("url").asText()).append(")\n")
                    .append(result.path("content").asText()).append("\n\n");
        }

        return sb.isEmpty() ? "No results found." : sb.toString().strip();
    }
}
