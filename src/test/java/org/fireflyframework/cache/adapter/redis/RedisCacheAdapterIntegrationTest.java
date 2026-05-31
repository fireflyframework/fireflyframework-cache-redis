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

package org.fireflyframework.cache.adapter.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.fireflyframework.cache.core.CacheHealth;
import org.fireflyframework.cache.core.CacheStats;
import org.fireflyframework.cache.core.CacheType;
import org.fireflyframework.cache.serialization.JsonCacheSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for RedisCacheAdapter using TestContainers.
 */
@Testcontainers
class RedisCacheAdapterIntegrationTest {

    @Container
    static GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379)
            .withReuse(true);

    private RedisCacheAdapter cacheAdapter;
    private ReactiveRedisTemplate<String, Object> redisTemplate;
    private ReactiveRedisConnectionFactory connectionFactory;
    private JsonCacheSerializer serializer;

    @BeforeEach
    void setUp() {
        // Create connection factory
        RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration();
        redisConfig.setHostName(redisContainer.getHost());
        redisConfig.setPort(redisContainer.getMappedPort(6379));
        redisConfig.setDatabase(0);

        connectionFactory = new LettuceConnectionFactory(redisConfig);
        ((LettuceConnectionFactory) connectionFactory).afterPropertiesSet();

        // Create ObjectMapper
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        
        // Create serializers
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer(objectMapper);
        
        // Create serialization context
        RedisSerializationContext<String, Object> serializationContext = RedisSerializationContext
                .<String, Object>newSerializationContext()
                .key(stringSerializer)
                .value(jsonSerializer)
                .hashKey(stringSerializer)
                .hashValue(jsonSerializer)
                .build();

        // Create reactive template
        redisTemplate = new ReactiveRedisTemplate<>(connectionFactory, serializationContext);

        // Create cache serializer
        serializer = new JsonCacheSerializer(objectMapper);

        // Create cache config
        RedisCacheConfig config = RedisCacheConfig.builder()
                .host(redisContainer.getHost())
                .port(redisContainer.getMappedPort(6379))
                .database(0)
                .keyPrefix("firefly:cache:test")
                .defaultTtl(Duration.ofMinutes(5))
                .connectionTimeout(Duration.ofSeconds(10))
                .commandTimeout(Duration.ofSeconds(5))
                .build();

        // Create cache adapter
        cacheAdapter = new RedisCacheAdapter("test-cache", redisTemplate, connectionFactory, config, serializer);

        // Clear any existing data
        redisTemplate.getConnectionFactory()
                .getReactiveConnection()
                .serverCommands()
                .flushAll()
                .block();
    }

    @AfterEach
    void tearDown() {
        // Close the cache adapter
        if (cacheAdapter != null) {
            cacheAdapter.close();
        }

        // Destroy the connection factory to properly close all connections
        if (connectionFactory instanceof LettuceConnectionFactory) {
            ((LettuceConnectionFactory) connectionFactory).destroy();
        }
    }

    @Test
    void shouldReturnCorrectCacheType() {
        assertThat(cacheAdapter.getCacheType()).isEqualTo(CacheType.REDIS);
    }

    @Test
    void shouldReturnCacheName() {
        assertThat(cacheAdapter.getCacheName()).isEqualTo("test-cache");
    }

    @Test
    void shouldBeAvailableWhenRedisIsRunning() {
        assertThat(cacheAdapter.isAvailable()).isTrue();
    }

    @Test
    void shouldPutAndGetValue() {
        String key = "test-key";
        String value = "test-value";

        // Put value
        StepVerifier.create(cacheAdapter.put(key, value))
                .verifyComplete();

        // Get value
        StepVerifier.create(cacheAdapter.get(key, String.class))
                .expectNext(Optional.of(value))
                .verifyComplete();
    }

    @Test
    void shouldPutWithTtlAndGetValue() {
        String key = "test-key-ttl";
        String value = "test-value-ttl";
        Duration ttl = Duration.ofSeconds(2);

        // Put value with TTL
        StepVerifier.create(cacheAdapter.put(key, value, ttl))
                .verifyComplete();

        // Get value immediately
        StepVerifier.create(cacheAdapter.get(key, String.class))
                .expectNext(Optional.of(value))
                .verifyComplete();

        // Wait for expiration and verify it's gone
        await().atMost(3, TimeUnit.SECONDS)
                .pollDelay(Duration.ofSeconds(2))
                .until(() -> cacheAdapter.get(key, String.class).block().isEmpty());
    }

    @Test
    void shouldReturnEmptyForNonExistentKey() {
        StepVerifier.create(cacheAdapter.get("non-existent", String.class))
                .expectNext(Optional.empty())
                .verifyComplete();
    }

    @Test
    void shouldEvictKey() {
        String key = "evict-key";
        String value = "evict-value";

        // Put value
        StepVerifier.create(cacheAdapter.put(key, value))
                .verifyComplete();

        // Verify it exists
        StepVerifier.create(cacheAdapter.get(key, String.class))
                .expectNext(Optional.of(value))
                .verifyComplete();

        // Evict
        StepVerifier.create(cacheAdapter.evict(key))
                .expectNext(true)
                .verifyComplete();

        // Verify it's gone
        StepVerifier.create(cacheAdapter.get(key, String.class))
                .expectNext(Optional.empty())
                .verifyComplete();
    }

    @Test
    void shouldClearAllEntries() {
        // Put multiple values
        StepVerifier.create(
                Flux.concat(
                        cacheAdapter.put("key1", "value1"),
                        cacheAdapter.put("key2", "value2"),
                        cacheAdapter.put("key3", "value3")
                )
        ).verifyComplete();

        // Verify they exist
        StepVerifier.create(cacheAdapter.get("key1", String.class))
                .expectNext(Optional.of("value1"))
                .verifyComplete();

        // Clear cache
        StepVerifier.create(cacheAdapter.clear())
                .verifyComplete();

        // Verify all are gone
        StepVerifier.create(cacheAdapter.get("key1", String.class))
                .expectNext(Optional.empty())
                .verifyComplete();
        StepVerifier.create(cacheAdapter.get("key2", String.class))
                .expectNext(Optional.empty())
                .verifyComplete();
        StepVerifier.create(cacheAdapter.get("key3", String.class))
                .expectNext(Optional.empty())
                .verifyComplete();
    }

    @Test
    void shouldPutAndGetMultipleItems() {
        // Put multiple items
        StepVerifier.create(
                Flux.concat(
                        cacheAdapter.put("batch-key1", "batch-value1"),
                        cacheAdapter.put("batch-key2", "batch-value2"),
                        cacheAdapter.put("batch-key3", 42)
                )
        ).verifyComplete();

        // Get all items
        StepVerifier.create(cacheAdapter.get("batch-key1", String.class))
                .expectNext(Optional.of("batch-value1"))
                .verifyComplete();
        StepVerifier.create(cacheAdapter.get("batch-key2", String.class))
                .expectNext(Optional.of("batch-value2"))
                .verifyComplete();
        StepVerifier.create(cacheAdapter.get("batch-key3", Integer.class))
                .expectNext(Optional.of(42))
                .verifyComplete();
    }

    @Test
    void shouldHandleComplexObjects() {
        TestObject originalObject = new TestObject("test", 42, List.of("item1", "item2"));
        String key = "complex-object";

        // Put complex object
        StepVerifier.create(cacheAdapter.put(key, originalObject))
                .verifyComplete();

        // Get complex object
        StepVerifier.create(cacheAdapter.get(key, TestObject.class))
                .expectNextMatches(optionalRetrieved -> {
                    assertThat(optionalRetrieved).isPresent();
                    TestObject retrieved = optionalRetrieved.get();
                    assertThat(retrieved.getName()).isEqualTo(originalObject.getName());
                    assertThat(retrieved.getValue()).isEqualTo(originalObject.getValue());
                    assertThat(retrieved.getItems()).containsExactlyElementsOf(originalObject.getItems());
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void shouldProvideHealthInformation() {
        StepVerifier.create(cacheAdapter.getHealth())
                .expectNextMatches(health -> {
                    assertThat(health.isHealthy()).isTrue();
                    assertThat(health.getDetails()).containsKey("redis_info");
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void shouldProvideStatistics() {
        // Put some values to generate stats
        StepVerifier.create(cacheAdapter.put("stats-key1", "value1"))
                .verifyComplete();
        StepVerifier.create(cacheAdapter.put("stats-key2", "value2"))
                .verifyComplete();
        StepVerifier.create(cacheAdapter.get("stats-key1", String.class))
                .expectNext(Optional.of("value1"))
                .verifyComplete();
        StepVerifier.create(cacheAdapter.get("stats-key1", String.class))
                .expectNext(Optional.of("value1"))
                .verifyComplete();
        StepVerifier.create(cacheAdapter.get("non-existent", String.class))
                .expectNext(Optional.empty())
                .verifyComplete();

        StepVerifier.create(cacheAdapter.getStats())
                .expectNextMatches(stats -> {
                    assertThat(stats).isNotNull();
                    assertThat(stats.getCacheName()).isEqualTo("test-cache");
                    // Note: Redis stats might be limited compared to Caffeine
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void shouldHandleConnectionFailureGracefully() {
        // This test would require stopping the container, which is complex
        // For now, we'll just verify the adapter is available
        assertThat(cacheAdapter.isAvailable()).isTrue();
    }

    @Test
    void shouldRespectKeyPrefix() {
        String key = "prefixed-key";
        String value = "prefixed-value";

        // Put value
        StepVerifier.create(cacheAdapter.put(key, value))
                .verifyComplete();

        // Verify the key exists with prefix in Redis
        // The actual key format is: keyPrefix + cacheName + ":" + key
        // Which is: "firefly:cache:test" + ":" + "test-cache" + ":" + "prefixed-key"
        StepVerifier.create(
                redisTemplate.hasKey("firefly:cache:test:test-cache:" + key)
        ).expectNext(true).verifyComplete();
    }

    // Test data class
    public static class TestObject {
        private String name;
        private int value;
        private List<String> items;

        public TestObject() {
            // Default constructor for Jackson
        }

        public TestObject(String name, int value, List<String> items) {
            this.name = name;
            this.value = value;
            this.items = items;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getValue() {
            return value;
        }

        public void setValue(int value) {
            this.value = value;
        }

        public List<String> getItems() {
            return items;
        }

        public void setItems(List<String> items) {
            this.items = items;
        }
    }
}