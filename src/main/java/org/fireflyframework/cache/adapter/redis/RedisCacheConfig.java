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

import lombok.Builder;
import lombok.Data;

import java.time.Duration;

/**
 * Configuration class for Redis cache adapter.
 * <p>
 * This class encapsulates all configuration options available for the Redis cache,
 * including connection settings, key prefixes, and expiration policies.
 */
@Data
@Builder
public class RedisCacheConfig {

    /**
     * Redis connection host.
     */
    @Builder.Default
    private final String host = "localhost";

    /**
     * Redis connection port.
     */
    @Builder.Default
    private final int port = 6379;

    /**
     * Redis database index.
     */
    @Builder.Default
    private final int database = 0;

    /**
     * Redis authentication password.
     */
    private final String password;

    /**
     * Redis username for ACL authentication.
     */
    private final String username;

    /**
     * Connection timeout.
     */
    @Builder.Default
    private final Duration connectionTimeout = Duration.ofSeconds(10);

    /**
     * Command timeout.
     */
    @Builder.Default
    private final Duration commandTimeout = Duration.ofSeconds(5);

    /**
     * Key prefix for all cache entries.
     * This helps organize cache keys and avoid collisions.
     */
    @Builder.Default
    private final String keyPrefix = "firefly:cache";

    /**
     * Default TTL for cache entries.
     * If not specified, entries will not expire automatically.
     */
    private final Duration defaultTtl;

    /**
     * Whether to enable key expiration events.
     */
    @Builder.Default
    private final boolean enableKeyspaceNotifications = false;

    /**
     * Maximum number of connections in the pool.
     */
    @Builder.Default
    private final int maxPoolSize = 8;

    /**
     * Minimum number of connections in the pool.
     */
    @Builder.Default
    private final int minPoolSize = 0;

    /**
     * Whether to use SSL/TLS for connection.
     */
    @Builder.Default
    private final boolean ssl = false;

    /**
     * Creates a default Redis cache configuration.
     *
     * @return default configuration with sensible defaults
     */
    public static RedisCacheConfig defaultConfig() {
        return RedisCacheConfig.builder()
                .host("localhost")
                .port(6379)
                .database(0)
                .connectionTimeout(Duration.ofSeconds(10))
                .commandTimeout(Duration.ofSeconds(5))
                .keyPrefix("firefly:cache")
                .maxPoolSize(8)
                .minPoolSize(0)
                .build();
    }

    /**
     * Creates a high-performance Redis cache configuration.
     * <p>
     * This configuration is optimized for high-throughput scenarios
     * with larger connection pools and shorter timeouts.
     *
     * @return high-performance configuration
     */
    public static RedisCacheConfig highPerformanceConfig() {
        return RedisCacheConfig.builder()
                .host("localhost")
                .port(6379)
                .database(0)
                .connectionTimeout(Duration.ofSeconds(5))
                .commandTimeout(Duration.ofSeconds(2))
                .keyPrefix("firefly:cache")
                .defaultTtl(Duration.ofHours(1))
                .maxPoolSize(16)
                .minPoolSize(2)
                .build();
    }

    /**
     * Creates a production Redis cache configuration.
     * <p>
     * This configuration includes longer timeouts and more conservative
     * settings suitable for production environments.
     *
     * @return production-ready configuration
     */
    public static RedisCacheConfig productionConfig() {
        return RedisCacheConfig.builder()
                .host("localhost")
                .port(6379)
                .database(0)
                .connectionTimeout(Duration.ofSeconds(30))
                .commandTimeout(Duration.ofSeconds(10))
                .keyPrefix("firefly:cache")
                .defaultTtl(Duration.ofMinutes(30))
                .enableKeyspaceNotifications(true)
                .maxPoolSize(12)
                .minPoolSize(1)
                .ssl(false)
                .build();
    }

    /**
     * Creates a secure Redis cache configuration with SSL.
     * <p>
     * This configuration enables SSL and includes security-focused settings.
     *
     * @return secure configuration with SSL enabled
     */
    public static RedisCacheConfig secureConfig() {
        return RedisCacheConfig.builder()
                .host("localhost")
                .port(6380) // Common SSL port
                .database(0)
                .connectionTimeout(Duration.ofSeconds(15))
                .commandTimeout(Duration.ofSeconds(8))
                .keyPrefix("firefly:cache")
                .ssl(true)
                .maxPoolSize(8)
                .minPoolSize(1)
                .build();
    }

    /**
     * Gets the Redis URL string for connection.
     *
     * @return Redis URL string
     */
    public String getRedisUrl() {
        StringBuilder url = new StringBuilder();
        url.append(ssl ? "rediss://" : "redis://");
        
        if (username != null && !username.trim().isEmpty()) {
            url.append(username);
            if (password != null && !password.trim().isEmpty()) {
                url.append(":").append(password);
            }
            url.append("@");
        } else if (password != null && !password.trim().isEmpty()) {
            url.append(":").append(password).append("@");
        }
        
        url.append(host).append(":").append(port);
        url.append("/").append(database);
        
        return url.toString();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("RedisCacheConfig{");
        sb.append("host='").append(host).append("'");
        sb.append(", port=").append(port);
        sb.append(", database=").append(database);
        
        if (username != null) {
            sb.append(", username='").append(username).append("'");
        }
        
        sb.append(", keyPrefix='").append(keyPrefix).append("'");
        
        if (defaultTtl != null) {
            sb.append(", defaultTtl=").append(defaultTtl);
        }
        
        sb.append(", maxPoolSize=").append(maxPoolSize);
        sb.append(", minPoolSize=").append(minPoolSize);
        sb.append(", ssl=").append(ssl);
        sb.append(", connectionTimeout=").append(connectionTimeout);
        sb.append(", commandTimeout=").append(commandTimeout);
        sb.append("}");
        
        return sb.toString();
    }
}