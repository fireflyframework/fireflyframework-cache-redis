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

import org.fireflyframework.cache.core.CacheAdapter;
import org.fireflyframework.cache.core.CacheHealth;
import org.fireflyframework.cache.core.CacheStats;
import org.fireflyframework.cache.core.CacheType;
import org.fireflyframework.cache.serialization.CacheSerializer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.ReactiveRedisConnection;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Redis-based cache adapter implementation.
 * <p>
 * This adapter provides distributed caching using Redis as the backend.
 * It supports time-based expiration, atomic operations, and comprehensive statistics.
 * <p>
 * Features:
 * <ul>
 *   <li>Distributed caching with Redis</li>
 *   <li>Time-based expiration (TTL)</li>
 *   <li>Atomic operations</li>
 *   <li>Reactive non-blocking API</li>
 *   <li>Comprehensive statistics</li>
 *   <li>Connection health monitoring</li>
 * </ul>
 */
@Slf4j
public class RedisCacheAdapter implements CacheAdapter {

    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final ReactiveRedisConnectionFactory connectionFactory;
    private final String cacheName;
    private final String keyPrefix;
    private final RedisCacheConfig config;
    private final CacheSerializer serializer;

    // Statistics tracking
    private final AtomicLong requestCount = new AtomicLong(0);
    private final AtomicLong hitCount = new AtomicLong(0);
    private final AtomicLong missCount = new AtomicLong(0);
    private final AtomicLong putCount = new AtomicLong(0);
    private final AtomicLong evictionCount = new AtomicLong(0);
    private final AtomicLong totalLoadTime = new AtomicLong(0);

    public RedisCacheAdapter(String cacheName, ReactiveRedisTemplate<String, Object> redisTemplate,
                            ReactiveRedisConnectionFactory connectionFactory, RedisCacheConfig config,
                            CacheSerializer serializer) {
        this.cacheName = cacheName;
        this.redisTemplate = redisTemplate;
        this.connectionFactory = connectionFactory;
        this.config = config;
        this.serializer = serializer;
        this.keyPrefix = buildKeyPrefix(cacheName, config.getKeyPrefix());
    }

    private String buildKeyPrefix(String cacheName, String configPrefix) {
        if (configPrefix != null && !configPrefix.trim().isEmpty()) {
            return configPrefix + ":" + cacheName + ":";
        }
        return "cache:" + cacheName + ":";
    }

    private String buildKey(Object key) {
        return keyPrefix + key.toString();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Mono<Optional<V>> get(K key) {
        return Mono.fromCallable(() -> {
            requestCount.incrementAndGet();
            long startTime = System.nanoTime();
            
            return redisTemplate.opsForValue()
                    .get(buildKey(key))
                    .cast(Object.class)
                    .map(value -> {
                        try {
                            V deserializedValue = serializer.deserialize(value, (Class<V>) Object.class);
                            hitCount.incrementAndGet();
                            totalLoadTime.addAndGet(System.nanoTime() - startTime);
                            log.debug("Cache hit for key '{}' in cache '{}'", key, cacheName);
                            return Optional.of(deserializedValue);
                        } catch (Exception e) {
                            log.warn("Failed to deserialize value for key '{}' in cache '{}': {}", 
                                    key, cacheName, e.getMessage());
                            missCount.incrementAndGet();
                            return Optional.<V>empty();
                        }
                    })
                    .switchIfEmpty(Mono.fromCallable(() -> {
                        missCount.incrementAndGet();
                        log.debug("Cache miss for key '{}' in cache '{}'", key, cacheName);
                        return Optional.<V>empty();
                    }));
        }).flatMap(mono -> mono);
    }

    @Override
    public <K, V> Mono<Optional<V>> get(K key, Class<V> valueType) {
        return Mono.fromCallable(() -> {
            requestCount.incrementAndGet();
            long startTime = System.nanoTime();
            
            return redisTemplate.opsForValue()
                    .get(buildKey(key))
                    .cast(Object.class)
                    .map(value -> {
                        try {
                            V deserializedValue = serializer.deserialize(value, valueType);
                            hitCount.incrementAndGet();
                            totalLoadTime.addAndGet(System.nanoTime() - startTime);
                            log.debug("Cache hit for key '{}' in cache '{}'", key, cacheName);
                            return Optional.of(deserializedValue);
                        } catch (Exception e) {
                            log.warn("Failed to deserialize value for key '{}' in cache '{}': {}", 
                                    key, cacheName, e.getMessage());
                            missCount.incrementAndGet();
                            return Optional.<V>empty();
                        }
                    })
                    .switchIfEmpty(Mono.fromCallable(() -> {
                        missCount.incrementAndGet();
                        log.debug("Cache miss for key '{}' in cache '{}'", key, cacheName);
                        return Optional.<V>empty();
                    }));
        }).flatMap(mono -> mono);
    }

    @Override
    public <K, V> Mono<Void> put(K key, V value) {
        return Mono.fromCallable(() -> {
            try {
                Object serializedValue = serializer.serialize(value);
                String redisKey = buildKey(key);
                
                Mono<Void> operation;
                if (config.getDefaultTtl() != null) {
                    operation = redisTemplate.opsForValue()
                            .set(redisKey, serializedValue, config.getDefaultTtl())
                            .then();
                } else {
                    operation = redisTemplate.opsForValue()
                            .set(redisKey, serializedValue)
                            .then();
                }
                
                return operation.doOnSuccess(unused -> {
                    putCount.incrementAndGet();
                    log.debug("Put value in cache '{}' for key '{}'", cacheName, key);
                });
            } catch (Exception e) {
                log.error("Error putting value in cache '{}' for key '{}': {}", 
                         cacheName, key, e.getMessage(), e);
                throw new RuntimeException("Failed to put value in cache", e);
            }
        }).flatMap(mono -> mono);
    }

    @Override
    public <K, V> Mono<Void> put(K key, V value, Duration ttl) {
        return Mono.fromCallable(() -> {
            try {
                Object serializedValue = serializer.serialize(value);
                String redisKey = buildKey(key);
                
                return redisTemplate.opsForValue()
                        .set(redisKey, serializedValue, ttl)
                        .then()
                        .doOnSuccess(unused -> {
                            putCount.incrementAndGet();
                            log.debug("Put value in cache '{}' for key '{}' with TTL {}", cacheName, key, ttl);
                        });
            } catch (Exception e) {
                log.error("Error putting value in cache '{}' for key '{}' with TTL: {}", 
                         cacheName, key, e.getMessage(), e);
                throw new RuntimeException("Failed to put value in cache with TTL", e);
            }
        }).flatMap(mono -> mono);
    }

    @Override
    public <K, V> Mono<Boolean> putIfAbsent(K key, V value) {
        return Mono.fromCallable(() -> {
            try {
                Object serializedValue = serializer.serialize(value);
                String redisKey = buildKey(key);
                
                Mono<Boolean> operation;
                if (config.getDefaultTtl() != null) {
                    operation = redisTemplate.opsForValue()
                            .setIfAbsent(redisKey, serializedValue, config.getDefaultTtl());
                } else {
                    operation = redisTemplate.opsForValue()
                            .setIfAbsent(redisKey, serializedValue);
                }
                
                return operation.doOnNext(success -> {
                    if (success) {
                        putCount.incrementAndGet();
                        log.debug("Put new value in cache '{}' for key '{}'", cacheName, key);
                    } else {
                        log.debug("Key '{}' already exists in cache '{}'", key, cacheName);
                    }
                });
            } catch (Exception e) {
                log.error("Error in putIfAbsent for cache '{}' and key '{}': {}", 
                         cacheName, key, e.getMessage(), e);
                throw new RuntimeException("Failed to put value if absent", e);
            }
        }).flatMap(mono -> mono);
    }

    @Override
    public <K, V> Mono<Boolean> putIfAbsent(K key, V value, Duration ttl) {
        return Mono.fromCallable(() -> {
            try {
                Object serializedValue = serializer.serialize(value);
                String redisKey = buildKey(key);
                
                return redisTemplate.opsForValue()
                        .setIfAbsent(redisKey, serializedValue, ttl)
                        .doOnNext(success -> {
                            if (success) {
                                putCount.incrementAndGet();
                                log.debug("Put new value in cache '{}' for key '{}' with TTL {}", 
                                         cacheName, key, ttl);
                            } else {
                                log.debug("Key '{}' already exists in cache '{}'", key, cacheName);
                            }
                        });
            } catch (Exception e) {
                log.error("Error in putIfAbsent with TTL for cache '{}' and key '{}': {}", 
                         cacheName, key, e.getMessage(), e);
                throw new RuntimeException("Failed to put value if absent with TTL", e);
            }
        }).flatMap(mono -> mono);
    }

    @Override
    public <K> Mono<Boolean> evict(K key) {
        return redisTemplate.delete(buildKey(key))
                .map(count -> {
                    boolean existed = count > 0;
                    if (existed) {
                        evictionCount.incrementAndGet();
                    }
                    log.debug("Evicted key '{}' from cache '{}': {}", key, cacheName, existed);
                    return existed;
                })
                .onErrorResume(e -> {
                    log.error("Error evicting key '{}' from cache '{}': {}", 
                             key, cacheName, e.getMessage(), e);
                    return Mono.just(false);
                });
    }

    @Override
    public Mono<Void> clear() {
        return getKeys()
                .flatMap(keys -> {
                    if (keys.isEmpty()) {
                        return Mono.<Long>just(0L);
                    }
                    return redisTemplate.delete(keys.toArray(new String[0]));
                })
                .doOnNext(deletedCount -> {
                    evictionCount.addAndGet(deletedCount);
                    log.debug("Cleared cache '{}', deleted {} keys", cacheName, deletedCount);
                })
                .then()
                .onErrorResume(e -> {
                    log.error("Error clearing cache '{}': {}", cacheName, e.getMessage(), e);
                    return Mono.empty();
                });
    }

    @Override
    public <K> Mono<Boolean> exists(K key) {
        return redisTemplate.hasKey(buildKey(key))
                .onErrorResume(e -> {
                    log.warn("Error checking existence of key '{}' in cache '{}': {}", 
                            key, cacheName, e.getMessage());
                    return Mono.just(false);
                });
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K> Mono<Set<K>> keys() {
        return getKeys()
                .map(redisKeys -> redisKeys.stream()
                        .map(this::extractOriginalKey)
                        .map(key -> (K) key)
                        .collect(Collectors.toSet()))
                .onErrorResume(e -> {
                    log.error("Error getting keys from cache '{}': {}", cacheName, e.getMessage(), e);
                    return Mono.just(Set.of());
                });
    }

    @Override
    public Mono<Long> size() {
        return getKeys()
                .map(keys -> (long) keys.size())
                .onErrorResume(e -> {
                    log.error("Error getting size of cache '{}': {}", cacheName, e.getMessage(), e);
                    return Mono.just(0L);
                });
    }

    @Override
    public Mono<CacheStats> getStats() {
        return Mono.fromCallable(() -> {
            long requests = requestCount.get();
            long hits = hitCount.get();
            long misses = missCount.get();
            long puts = putCount.get();
            long evictions = evictionCount.get();
            long totalTime = totalLoadTime.get();
            
            double averageTime = requests > 0 ? (double) totalTime / requests : 0.0;
            
            return CacheStats.builder()
                    .requestCount(requests)
                    .hitCount(hits)
                    .missCount(misses)
                    .loadCount(puts)
                    .evictionCount(evictions)
                    .entryCount(0L) // Will be filled asynchronously
                    .averageLoadTime(averageTime)
                    .estimatedSize(0L) // Not easily available in Redis
                    .capturedAt(Instant.now())
                    .cacheType(CacheType.REDIS)
                    .cacheName(cacheName)
                    .build();
        }).flatMap(stats -> 
            size().map(entryCount -> 
                CacheStats.builder()
                        .requestCount(stats.getRequestCount())
                        .hitCount(stats.getHitCount())
                        .missCount(stats.getMissCount())
                        .loadCount(stats.getLoadCount())
                        .evictionCount(stats.getEvictionCount())
                        .entryCount(entryCount)
                        .averageLoadTime(stats.getAverageLoadTime())
                        .estimatedSize(entryCount * 100) // Rough estimate
                        .capturedAt(stats.getCapturedAt())
                        .cacheType(CacheType.REDIS)
                        .cacheName(cacheName)
                        .build()
            ).onErrorReturn(stats)
        ).onErrorReturn(CacheStats.empty(CacheType.REDIS, cacheName));
    }

    @Override
    public CacheType getCacheType() {
        return CacheType.REDIS;
    }

    @Override
    public String getCacheName() {
        return cacheName;
    }

    @Override
    public boolean isAvailable() {
        return connectionFactory != null && redisTemplate != null;
    }

    @Override
    public Mono<CacheHealth> getHealth() {
        return Mono.fromCallable(() -> {
            long startTime = System.currentTimeMillis();

            // Perform a simple health check
            String testKey = buildKey("__health_check__" + System.currentTimeMillis());
            return redisTemplate.opsForValue()
                    .set(testKey, "test", Duration.ofSeconds(10))
                    .then(redisTemplate.opsForValue().get(testKey))
                    .then(redisTemplate.delete(testKey))
                    .flatMap(deletedCount -> {
                        long responseTime = System.currentTimeMillis() - startTime;

                        // Get Redis info
                        return connectionFactory.getReactiveConnection()
                                .serverCommands()
                                .info()
                                .map(properties -> {
                                    return CacheHealth.builder()
                                            .status("UP")
                                            .cacheType(CacheType.REDIS)
                                            .cacheName(cacheName)
                                            .available(true)
                                            .configured(true)
                                            .responseTimeMs(responseTime)
                                            .lastSuccessfulOperation(Instant.now())
                                            .consecutiveFailures(0)
                                            .details(java.util.Map.of(
                                                    "redis_info", properties.toString(),
                                                    "host", config.getHost(),
                                                    "port", config.getPort(),
                                                    "database", config.getDatabase(),
                                                    "key_prefix", keyPrefix
                                            ))
                                            .build();
                                })
                                .onErrorResume(infoError -> {
                                    // If we can't get info, still return healthy but without details
                                    log.warn("Could not get Redis info for cache '{}': {}",
                                            cacheName, infoError.getMessage());
                                    return Mono.just(CacheHealth.builder()
                                            .status("UP")
                                            .cacheType(CacheType.REDIS)
                                            .cacheName(cacheName)
                                            .available(true)
                                            .configured(true)
                                            .responseTimeMs(responseTime)
                                            .lastSuccessfulOperation(Instant.now())
                                            .consecutiveFailures(0)
                                            .details(java.util.Map.of(
                                                    "host", config.getHost(),
                                                    "port", config.getPort(),
                                                    "database", config.getDatabase(),
                                                    "key_prefix", keyPrefix
                                            ))
                                            .build());
                                });
                    })
                    .onErrorResume(e -> {
                        log.error("Health check failed for cache '{}': {}", cacheName, e.getMessage(), e);
                        return Mono.just(CacheHealth.unhealthy(CacheType.REDIS, cacheName,
                                "Health check failed: " + e.getMessage(), e));
                    });
        }).flatMap(mono -> mono);
    }

    @Override
    public void close() {
        try {
            log.info("Closing Redis cache adapter '{}'", cacheName);
            // Connection factory is typically managed by Spring, so we don't close it here
        } catch (Exception e) {
            log.error("Error closing cache '{}': {}", cacheName, e.getMessage(), e);
        }
    }

    /**
     * Gets all keys matching the cache prefix.
     *
     * @return a Mono containing the set of Redis keys
     */
    private Mono<Set<String>> getKeys() {
        return redisTemplate.keys(keyPrefix + "*")
                .collect(Collectors.toSet());
    }

    /**
     * Extracts the original key from a Redis key by removing the prefix.
     *
     * @param redisKey the full Redis key
     * @return the original key
     */
    private String extractOriginalKey(String redisKey) {
        if (redisKey.startsWith(keyPrefix)) {
            return redisKey.substring(keyPrefix.length());
        }
        return redisKey;
    }

    /**
     * Gets the underlying Redis cache configuration.
     *
     * @return the cache configuration
     */
    public RedisCacheConfig getConfig() {
        return config;
    }

    /**
     * Gets the Redis template used by this cache.
     *
     * @return the Redis template
     */
    public ReactiveRedisTemplate<String, Object> getRedisTemplate() {
        return redisTemplate;
    }
}