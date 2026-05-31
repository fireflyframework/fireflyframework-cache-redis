/*
 * Copyright 2024-2026 Firefly Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fireflyframework.cache.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.fireflyframework.cache.properties.CacheProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Auto-configuration for Redis infrastructure beans used by fireflyframework-cache.
 *
 * This configuration is only loaded when Redis classes are on the classpath. It
 * provides a ReactiveRedisConnectionFactory and ReactiveRedisTemplate when a
 * Redis host is configured. Cache adapters themselves are created on-demand by
 * CacheManagerFactory via RedisCacheHelper to avoid premature bean creation and
 * to support multiple independent caches per application.
 */
@AutoConfiguration(after = CacheAutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.data.redis.core.ReactiveRedisTemplate")
@Slf4j
public class RedisCacheAutoConfiguration {

    /**
     * Creates a Redis connection factory when no existing bean is present and a
     * Redis host is configured.
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(ReactiveRedisConnectionFactory.class)
    @ConditionalOnExpression("${firefly.cache.redis.enabled:true} && '${firefly.cache.redis.host:}'.length() > 0")
    public ReactiveRedisConnectionFactory redisConnectionFactory(CacheProperties properties) {
        log.debug("Creating Redis connection factory from Firefly cache properties");
        CacheProperties.RedisConfig redisProps = properties.getRedis();

        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration();
        serverConfig.setHostName(redisProps.getHost());
        serverConfig.setPort(redisProps.getPort());
        serverConfig.setDatabase(redisProps.getDatabase());

        if (redisProps.getPassword() != null) {
            serverConfig.setPassword(redisProps.getPassword());
        }
        if (redisProps.getUsername() != null) {
            serverConfig.setUsername(redisProps.getUsername());
        }

        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(redisProps.getCommandTimeout())
                .build();

        log.info("   • Redis host: {}:{}", redisProps.getHost(), redisProps.getPort());
        log.info("   • Redis database: {}", redisProps.getDatabase());

        return new LettuceConnectionFactory(serverConfig, clientConfig);
    }

    /**
     * Creates a ReactiveRedisTemplate backed by the configured connection
     * factory when one is not already present.
     */
    @Bean
    @ConditionalOnMissingBean(ReactiveRedisTemplate.class)
    @ConditionalOnBean(ReactiveRedisConnectionFactory.class)
    public ReactiveRedisTemplate<String, Object> reactiveRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory,
            @Qualifier("cacheObjectMapper") ObjectMapper objectMapper) {
        log.debug("Creating ReactiveRedisTemplate from connection factory");

        // Configure serializers
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer(objectMapper);

        RedisSerializationContext<String, Object> serializationContext = RedisSerializationContext
                .<String, Object>newSerializationContext()
                .key(stringSerializer)
                .value(jsonSerializer)
                .hashKey(stringSerializer)
                .hashValue(jsonSerializer)
                .build();

        return new ReactiveRedisTemplate<>(connectionFactory, serializationContext);
    }
}
