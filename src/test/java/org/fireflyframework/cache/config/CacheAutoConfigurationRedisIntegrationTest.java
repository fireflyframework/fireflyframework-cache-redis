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

import org.fireflyframework.cache.core.CacheType;
import org.fireflyframework.cache.manager.FireflyCacheManager;
import org.fireflyframework.cache.properties.CacheProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for CacheAutoConfiguration with Redis using TestContainers.
 */
@Testcontainers
class CacheAutoConfigurationRedisIntegrationTest {

    @Container
    static GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379)
            .withReuse(true);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CacheAutoConfiguration.class, RedisCacheAutoConfiguration.class));

    @Test
    void shouldCreateRedisBeansWhenRedisIsConfigured() {
        contextRunner
                .withPropertyValues(
                        "firefly.cache.enabled=true",
                        "firefly.cache.default-cache-type=REDIS",
                        "firefly.cache.redis.host=" + redisContainer.getHost(),
                        "firefly.cache.redis.port=" + redisContainer.getMappedPort(6379),
                        "firefly.cache.redis.enabled=true"
                )
                .run(context -> {
                    // Verify core beans exist
                    assertThat(context).hasSingleBean(CacheProperties.class);
                    assertThat(context).hasSingleBean(FireflyCacheManager.class);
                    
                    // Verify Redis-specific beans
                    assertThat(context).hasSingleBean(ReactiveRedisConnectionFactory.class);
                    assertThat(context).hasSingleBean(ReactiveRedisTemplate.class);
                    
                    // Verify configuration is correct
                    CacheProperties properties = context.getBean(CacheProperties.class);
                    assertThat(properties.getDefaultCacheType()).isEqualTo(CacheType.REDIS);

                    CacheProperties.RedisConfig redisConfig = properties.getRedis();
                    assertThat(redisConfig.getHost()).isEqualTo(redisContainer.getHost());
                    assertThat(redisConfig.getPort()).isEqualTo(redisContainer.getMappedPort(6379));
                    assertThat(redisConfig.isEnabled()).isTrue();
                });
    }

    @Test
    void shouldDefaultToCaffeineWhenNoRedisConfiguration() {
        contextRunner
                .withPropertyValues(
                        "firefly.cache.enabled=true"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(CacheProperties.class);
                    assertThat(context).hasSingleBean(FireflyCacheManager.class);
                    
                    // Should not create Redis beans without configuration
                    assertThat(context).doesNotHaveBean(ReactiveRedisConnectionFactory.class);
                    assertThat(context).doesNotHaveBean(ReactiveRedisTemplate.class);
                    
                    // Should default to Caffeine
                    CacheProperties properties = context.getBean(CacheProperties.class);
                    assertThat(properties.getDefaultCacheType()).isEqualTo(CacheType.CAFFEINE);
                });
    }

    @Test
    void shouldCreateBothCaffeineAndRedisWhenBothEnabled() {
        contextRunner
                .withPropertyValues(
                        "firefly.cache.enabled=true",
                        "firefly.cache.default-cache-type=AUTO",
                        "firefly.cache.caffeine.enabled=true",
                        "firefly.cache.redis.host=" + redisContainer.getHost(),
                        "firefly.cache.redis.port=" + redisContainer.getMappedPort(6379),
                        "firefly.cache.redis.enabled=true"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(CacheProperties.class);
                    assertThat(context).hasSingleBean(FireflyCacheManager.class);
                    
                    // Should have both Caffeine and Redis components
                    assertThat(context).hasSingleBean(ReactiveRedisConnectionFactory.class);
                    assertThat(context).hasSingleBean(ReactiveRedisTemplate.class);
                    
                    // Verify cache manager has adapters (indirectly)
                    FireflyCacheManager cacheManager = context.getBean(FireflyCacheManager.class);
                    assertThat(cacheManager).isNotNull();
                });
    }

    @Test
    void shouldRespectRedisConfigurationProperties() {
        contextRunner
                .withPropertyValues(
                        "firefly.cache.enabled=true",
                        "firefly.cache.redis.host=" + redisContainer.getHost(),
                        "firefly.cache.redis.port=" + redisContainer.getMappedPort(6379),
                        "firefly.cache.redis.database=2",
                        "firefly.cache.redis.key-prefix=test:prefix",
                        "firefly.cache.redis.connection-timeout=PT15S",
                        "firefly.cache.redis.command-timeout=PT10S",
                        "firefly.cache.redis.enabled=true"
                )
                .run(context -> {
                    CacheProperties properties = context.getBean(CacheProperties.class);
                    CacheProperties.RedisConfig redisConfig = properties.getRedis();

                    assertThat(redisConfig.getHost()).isEqualTo(redisContainer.getHost());
                    assertThat(redisConfig.getPort()).isEqualTo(redisContainer.getMappedPort(6379));
                    assertThat(redisConfig.getDatabase()).isEqualTo(2);
                    assertThat(redisConfig.getKeyPrefix()).isEqualTo("test:prefix");
                    assertThat(redisConfig.getConnectionTimeout().getSeconds()).isEqualTo(15);
                    assertThat(redisConfig.getCommandTimeout().getSeconds()).isEqualTo(10);
                });
    }

    @Test
    void shouldNotCreateRedisBeansWhenRedisDisabled() {
        contextRunner
                .withPropertyValues(
                        "firefly.cache.enabled=true",
                        "firefly.cache.redis.enabled=false",
                        "firefly.cache.redis.host=" + redisContainer.getHost(),
                        "firefly.cache.redis.port=" + redisContainer.getMappedPort(6379)
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(CacheProperties.class);
                    assertThat(context).hasSingleBean(FireflyCacheManager.class);
                    
                    // Should not create Redis beans when disabled
                    assertThat(context).doesNotHaveBean(ReactiveRedisConnectionFactory.class);
                    assertThat(context).doesNotHaveBean(ReactiveRedisTemplate.class);
                });
    }

    @Test
    void shouldValidateFireflyPropertiesStructure() {
        contextRunner
                .withPropertyValues(
                        "firefly.cache.enabled=true",
                        "firefly.cache.default-cache-name=my-cache",
                        "firefly.cache.default-cache-type=CAFFEINE",
                        "firefly.cache.metrics-enabled=false",
                        "firefly.cache.health-enabled=true",
                        "firefly.cache.stats-enabled=false"
                )
                .run(context -> {
                    CacheProperties properties = context.getBean(CacheProperties.class);

                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getDefaultCacheType()).isEqualTo(CacheType.CAFFEINE);
                    assertThat(properties.isMetricsEnabled()).isFalse();
                    assertThat(properties.isHealthEnabled()).isTrue();
                    assertThat(properties.isStatsEnabled()).isFalse();
                });
    }
}