package com.itplace.userapi.common.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

class RedisConfigTest {
    @Test
    void bootAppliesCredentialsDatabaseSslAndDeadlinesToTheActualFactory() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withUserConfiguration(RedisConfig.class)
                .withPropertyValues("spring.data.redis.host=127.0.0.1", "spring.data.redis.port=6380",
                        "spring.data.redis.username=map-reader", "spring.data.redis.password=test-password",
                        "spring.data.redis.database=3", "spring.data.redis.ssl.enabled=true",
                        "spring.data.redis.timeout=1800ms", "spring.data.redis.connect-timeout=900ms")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(LettuceConnectionFactory.class);
                    var factory = context.getBean(LettuceConnectionFactory.class);
                    assertThat(factory.getStandaloneConfiguration().getUsername()).isEqualTo("map-reader");
                    assertThat(factory.getStandaloneConfiguration().getPassword().get()).containsExactly("test-password".toCharArray());
                    assertThat(factory.getDatabase()).isEqualTo(3);
                    assertThat(factory.getHostName()).isEqualTo("127.0.0.1");
                    assertThat(factory.getPort()).isEqualTo(6380);
                    assertThat(factory.getClientConfiguration().isUseSsl()).isTrue();
                    assertThat(factory.getClientConfiguration().getCommandTimeout()).isEqualTo(Duration.ofMillis(1800));
                    assertThat(factory.getClientConfiguration().getClientOptions().orElseThrow()
                            .getSocketOptions().getConnectTimeout()).isEqualTo(Duration.ofMillis(900));
                });
    }
}
