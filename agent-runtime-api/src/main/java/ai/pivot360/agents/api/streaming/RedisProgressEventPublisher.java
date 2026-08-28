package ai.pivot360.agents.api.streaming;

import ai.pivot360.agents.common.event.ProgressEvent;
import ai.pivot360.agents.common.event.ProgressEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisProgressEventPublisher implements ProgressEventPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisProgressEventPublisher(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(ProgressEvent event) {
        String channel = RedisChannels.forRun(event.tenantId(), event.agentRunId());
        try {
            redisTemplate.convertAndSend(channel, objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize progress event", e);
        }
    }
}
