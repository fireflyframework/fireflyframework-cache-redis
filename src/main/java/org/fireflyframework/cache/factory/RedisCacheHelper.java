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

package org.fireflyframework.cache.factory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.fireflyframework.cache.adapter.redis.RedisCacheAdapter;
import org.fireflyframework.cache.adapter.redis.RedisCacheConfig;
import org.fireflyframework.cache.core.CacheAdapter;
import org.fireflyframework.cache.properties.CacheProperties;
import org.fireflyframework.cache.serialization.CacheSerializer;
import org.fireflyframework.cache.serialization.JsonCacheSerializer;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Helper class for creating Redis cache adapters.
 * <p>
 * This class is separated from CacheManagerFactory to isolate Redis dependencies.
 * It will only be loaded when Redis classes are available on the classpath.
 */
public class RedisCacheHelper {

    /**
     * Creates a Redis cache adapter.
     *
     * @param cacheName the cache name
     * @param keyPrefix the key prefix
     * @param defaultTtl the default TTL
     * @param redisConnectionFactory the Redis connection factory
     * @param properties the cache properties
     * @param objectMapper the object mapper
     * @return a configured Redis cache adapter
     */
    public static CacheAdapter createRedisCacheAdapter(
            String cacheName,
            String keyPrefix,
            Duration defaultTtl,
            ReactiveRedisConnectionFactory redisConnectionFactory,
            CacheProperties properties,
            ObjectMapper objectMapper) {

        if (redisConnectionFactory == null) {
            throw new IllegalStateException("Redis connection factory is required for Redis cache");
        }

        CacheProperties.RedisConfig redisProps = properties.getRedis();
        CacheSerializer serializer = new JsonCacheSerializer(objectMapper);

        // Create Redis template with custom key prefix
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer(objectMapper);

        RedisSerializationContext<String, Object> serializationContext = RedisSerializationContext
                .<String, Object>newSerializationContext()
                .key(stringSerializer)
                .value(jsonSerializer)
                .hashKey(stringSerializer)
                .hashValue(jsonSerializer)
                .build();

        ReactiveRedisTemplate<String, Object> redisTemplate =
                new ReactiveRedisTemplate<>(redisConnectionFactory, serializationContext);

        RedisCacheConfig config = RedisCacheConfig.builder()
                .host(redisProps.getHost())
                .port(redisProps.getPort())
                .database(redisProps.getDatabase())
                .password(redisProps.getPassword())
                .username(redisProps.getUsername())
                .connectionTimeout(redisProps.getConnectionTimeout())
                .commandTimeout(redisProps.getCommandTimeout())
                .keyPrefix(keyPrefix)
                .defaultTtl(defaultTtl != null ? defaultTtl : redisProps.getDefaultTtl())
                .enableKeyspaceNotifications(redisProps.isEnableKeyspaceNotifications())
                .maxPoolSize(redisProps.getMaxPoolSize())
                .minPoolSize(redisProps.getMinPoolSize())
                .ssl(redisProps.isSsl())
                .build();

        return new RedisCacheAdapter(cacheName, redisTemplate, redisConnectionFactory, config, serializer);
    }
}
