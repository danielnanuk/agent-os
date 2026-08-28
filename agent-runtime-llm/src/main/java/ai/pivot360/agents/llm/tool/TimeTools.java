package ai.pivot360.agents.llm.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/** Minimal demo tool used to prove out the end-to-end tool-calling vertical slice. */
@Component
public class TimeTools implements AgentTool {

    @Tool(description = "Get the current date and time in ISO-8601 format")
    public String get_current_time() {
        return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
