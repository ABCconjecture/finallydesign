package com.example.bysjdesign.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.ConfigureRedisAction;

@Configuration
@ConditionalOnProperty(name = "spring.session.store-type", havingValue = "redis", matchIfMissing = true)
public class RedisSessionConfig {

    @Bean
    public ConfigureRedisAction configureRedisAction() {
        // Compatible with managed Redis services that disable CONFIG commands.
        return ConfigureRedisAction.NO_OP;
    }
}
