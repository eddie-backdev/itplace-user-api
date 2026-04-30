package com.itplace.userapi.chat.config;

import com.itplace.userapi.chat.redis.UserChatRedisSubscriber;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
@RequiredArgsConstructor
public class UserRedisSubscriberConfig {

    private final RedisConnectionFactory redisConnectionFactory;
    private final UserChatRedisSubscriber userChatRedisSubscriber;

    @Bean
    public RedisMessageListenerContainer userRedisMessageListenerContainer() {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redisConnectionFactory);
        container.addMessageListener(userChatRedisSubscriber,
                new ChannelTopic("chat:event:admin-message"));
        return container;
    }
}
