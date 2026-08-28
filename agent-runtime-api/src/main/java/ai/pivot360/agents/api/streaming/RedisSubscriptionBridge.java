package ai.pivot360.agents.api.streaming;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Relays Redis pub/sub messages to whatever local {@link SseEmitter}s (via
 * {@link SseSubscriptionManager}) are held by this JVM instance -- the mechanism that
 * lets a Temporal Activity running on a *different* instance's worker still reach a
 * client's SSE connection on *this* instance.
 */
@Component
public class RedisSubscriptionBridge implements MessageListener {

    private final RedisMessageListenerContainer container;
    private final SseSubscriptionManager subscriptionManager;
    private final Set<UUID> activeSubscriptions = ConcurrentHashMap.newKeySet();

    public RedisSubscriptionBridge(RedisMessageListenerContainer container,
                                    SseSubscriptionManager subscriptionManager) {
        this.container = container;
        this.subscriptionManager = subscriptionManager;
    }

    public void subscribe(String tenantId, UUID agentRunId) {
        if (activeSubscriptions.add(agentRunId)) {
            container.addMessageListener(this, new ChannelTopic(RedisChannels.forRun(tenantId, agentRunId)));
        }
    }

    public void unsubscribe(String tenantId, UUID agentRunId) {
        if (activeSubscriptions.remove(agentRunId)) {
            container.removeMessageListener(this, new ChannelTopic(RedisChannels.forRun(tenantId, agentRunId)));
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        String runIdSegment = channel.substring(channel.lastIndexOf(':') + 1);
        UUID agentRunId = UUID.fromString(runIdSegment);
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        subscriptionManager.emit(agentRunId, "progress", payload);
    }
}
