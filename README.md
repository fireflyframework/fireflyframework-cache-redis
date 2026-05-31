# Firefly Framework - Cache Redis

[![CI](https://github.com/fireflyframework/fireflyframework-cache-redis/actions/workflows/ci.yml/badge.svg)](https://github.com/fireflyframework/fireflyframework-cache-redis/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://openjdk.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green.svg)](https://spring.io/projects/spring-boot)

> Redis distributed-cache provider adapter for the Firefly Framework reactive cache abstraction — a non-blocking `CacheAdapter` backed by Spring Data Redis and the Lettuce reactive driver.

---

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Configuration](#configuration)
- [Documentation](#documentation)
- [Contributing](#contributing)
- [License](#license)

## Overview

`fireflyframework-cache-redis` is a **pluggable provider adapter** for the Firefly Framework cache abstraction. It implements the `CacheAdapter` SPI defined in [`fireflyframework-cache`](https://github.com/fireflyframework/fireflyframework-cache) using Redis as a distributed backend, so application code depends only on the core abstraction while the actual cache lives in Redis.

The core module ships an in-process Caffeine cache by default (`CacheType.CAFFEINE`). When you need a **distributed cache shared across multiple service instances** — to keep entries consistent behind a load balancer, survive individual pod restarts, or act as the L2 tier of a smart multi-tier cache — you add this adapter and select the `REDIS` cache type. Every operation (`get`, `put`, `putIfAbsent`, `evict`, `clear`, `exists`, `keys`, `size`, `getStats`, `getHealth`) is fully reactive and returns Project Reactor types, built on `ReactiveRedisTemplate` and Lettuce.

The adapter registers itself with the core through Java's `ServiceLoader`: `RedisProvider` (a `CacheProviderFactory` listed in `META-INF/services/org.fireflyframework.cache.spi.CacheProviderFactory`) advertises `CacheType.REDIS` with priority `10` and becomes available once the Redis classes are on the classpath, a connection factory exists, and `firefly.cache.redis.enabled` is `true`. The core `CacheManagerFactory` then builds `RedisCacheAdapter` instances on demand via `RedisCacheHelper`, so no adapter beans are created prematurely and a single application can hold several independent caches. Redis infrastructure beans (`ReactiveRedisConnectionFactory`, `ReactiveRedisTemplate`) are contributed by `RedisCacheAutoConfiguration` when a Redis host is configured.

Within the Firefly cache family, this is one of four provider adapters that plug into the same core SPI. The active provider is chosen with `firefly.cache.default-cache-type` (or `AUTO` for availability-based selection) in the core module:

- [`fireflyframework-cache`](https://github.com/fireflyframework/fireflyframework-cache) — the abstraction (`CacheAdapter`, `FireflyCacheManager`, `CacheStats`, `CacheHealth`, `CacheType`) plus the default Caffeine provider
- **`fireflyframework-cache-redis`** — this module: distributed Redis provider (`CacheType.REDIS`)
- [`fireflyframework-cache-hazelcast`](https://github.com/fireflyframework/fireflyframework-cache-hazelcast) — in-memory data grid provider (`CacheType.HAZELCAST`)
- [`fireflyframework-cache-jcache`](https://github.com/fireflyframework/fireflyframework-cache-jcache) — JSR-107 (JCache) provider (`CacheType.JCACHE`)
- [`fireflyframework-cache-postgres`](https://github.com/fireflyframework/fireflyframework-cache-postgres) — SQL-backed provider over PostgreSQL (`CacheType.POSTGRES`)

## Features

- **Reactive, non-blocking** — implements the core `CacheAdapter` SPI on top of `ReactiveRedisTemplate`; no blocking calls on the request path.
- **Distributed by design** — a shared Redis server provides a single, consistent cache across all instances of a service; works standalone or as the L2 tier of the core smart (L1+L2) cache.
- **Rich operation set** — `get`/`get(type)`, `put`, `put` with TTL, `putIfAbsent` (atomic `SETNX`, with optional TTL), `evict`, `clear`, `exists`, `keys`, `size`, statistics and health.
- **Per-entry and default TTL** — `put(key, value, ttl)` honours an explicit time-to-live; otherwise a configurable `default-ttl` is applied (no automatic expiry when unset).
- **Namespaced keys** — keys are prefixed (`<key-prefix>:<cacheName>:` , default prefix `firefly:cache`) so framework entries are isolated and `clear()`/`keys()` scan only this cache.
- **JSON value serialization** — values are serialized with the framework's `JsonCacheSerializer` / `GenericJackson2JsonRedisSerializer` and keys with `StringRedisSerializer`, producing human-readable, language-neutral entries.
- **Statistics and health** — `getStats()` reports requests, hits, misses, puts, evictions, entry count and average load time as `CacheStats`; `getHealth()` performs a live round-trip and returns `CacheHealth` including Redis `INFO` details.
- **On-demand adapter creation** — `RedisProvider` + `RedisCacheHelper` let the core `CacheManagerFactory` create adapters lazily, keeping Redis dependencies isolated until actually used.
- **Auto-configuration** — `RedisCacheAutoConfiguration` wires the Lettuce-backed `ReactiveRedisConnectionFactory` and `ReactiveRedisTemplate` from Firefly cache properties; both beans are `@ConditionalOnMissingBean`, so your own definitions take precedence.

## Requirements

- Java 21+ (Java 25 recommended)
- Spring Boot 3.x
- Maven 3.9+
- A reachable **Redis** server (Redis 6+; connection via the Lettuce reactive driver)

## Installation

Add the adapter alongside the cache core. The version is managed by the Firefly parent/BOM, so no explicit `<version>` is required when you inherit the parent.

```xml
<dependency>
    <groupId>org.fireflyframework</groupId>
    <artifactId>fireflyframework-cache-redis</artifactId>
    <!-- version managed by fireflyframework-parent / BOM -->
</dependency>
```

This adapter declares `fireflyframework-cache` as a dependency, so adding it brings the core abstraction with it. Inherit the Firefly parent to get managed versions:

```xml
<parent>
    <groupId>org.fireflyframework</groupId>
    <artifactId>fireflyframework-parent</artifactId>
    <version>26.05.08</version>
</parent>
```

## Quick Start

**1. Add the dependency** (see [Installation](#installation)).

**2. Select Redis as the cache type and enable the adapter** in `application.yml`:

```yaml
firefly:
  cache:
    enabled: true
    default-cache-type: REDIS    # core selects the active provider (CAFFEINE | REDIS | HAZELCAST | JCACHE | POSTGRES | AUTO)
    redis:
      enabled: true              # the Redis provider activates only when true
      host: localhost
      port: 6379
      default-ttl: PT30M
```

**3. Inject and use the cache** through the core abstraction — your code never references Redis directly:

```java
import org.fireflyframework.cache.core.CacheAdapter;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;

@Service
public class ProductService {

    private final CacheAdapter cache;

    public ProductService(CacheAdapter cache) {
        this.cache = cache;
    }

    public Mono<Product> getProduct(String id) {
        return cache.<String, Product>get(id, Product.class)
                .flatMap(opt -> opt.map(Mono::just)
                        .orElseGet(() -> loadFromDatabase(id)
                                .flatMap(p -> cache.put(id, p, Duration.ofMinutes(30))
                                        .thenReturn(p))));
    }

    public Mono<Boolean> invalidate(String id) {
        return cache.evict(id);
    }
}
```

Swapping providers later is a configuration change only: point `firefly.cache.default-cache-type` at another type and add that adapter's dependency — application code is untouched. With `default-cache-type: AUTO`, the framework prefers Redis when it is configured and available, then Hazelcast, JCache, Caffeine, and finally a no-op cache.

## Configuration

Redis properties live under `firefly.cache.redis.*` and are bound by the core `CacheProperties.RedisConfig`. Provider selection (`firefly.cache.default-cache-type`) and the master switch (`firefly.cache.enabled`) belong to the core module. The block below shows the real keys with their defaults:

```yaml
firefly:
  cache:
    enabled: true                 # master switch for the cache abstraction (default: true)
    default-cache-type: REDIS     # active provider (default: CAFFEINE)
    redis:
      enabled: true               # Redis provider switch (default: true; provider still needs a host + Redis on classpath)
      cache-name: default         # logical cache name (default: default)
      host: localhost             # Redis host (default: localhost)
      port: 6379                  # Redis port (default: 6379)
      database: 0                 # Redis database index (default: 0)
      username:                   # ACL username (optional)
      password:                   # auth password (optional)
      key-prefix: firefly:cache   # key namespace prefix (default: firefly:cache)
      default-ttl:                # default TTL; unset = entries never expire automatically
      connection-timeout: PT10S   # connection timeout (default: 10s)
      command-timeout: PT5S       # command timeout (default: 5s)
      max-pool-size: 8            # max pooled connections (default: 8)
      min-pool-size: 0            # min pooled connections (default: 0)
      ssl: false                  # use TLS (rediss://) (default: false)
      enable-keyspace-notifications: false  # subscribe to key-expiry events (default: false)
```

| Property | Default | Description |
| --- | --- | --- |
| `firefly.cache.default-cache-type` | `CAFFEINE` | Core: active provider. Set to `REDIS` to select this adapter, or `AUTO` for availability-based selection. |
| `firefly.cache.redis.enabled` | `true` | Whether the Redis provider may activate. The provider also needs a configured `host` and Redis on the classpath. |
| `firefly.cache.redis.host` / `port` / `database` | `localhost` / `6379` / `0` | Redis connection coordinates. |
| `firefly.cache.redis.username` / `password` | _(unset)_ | Optional ACL username and auth password. |
| `firefly.cache.redis.key-prefix` | `firefly:cache` | Namespace prefix; effective key pattern is `<key-prefix>:<cacheName>:<key>`. Scopes `clear()`/`keys()`. |
| `firefly.cache.redis.default-ttl` | _(unset)_ | TTL applied to `put` without an explicit TTL. When unset, entries do not expire automatically. ISO-8601 duration (`PT30M`). |
| `firefly.cache.redis.connection-timeout` | `PT10S` | Lettuce connection timeout. |
| `firefly.cache.redis.command-timeout` | `PT5S` | Lettuce command timeout. |
| `firefly.cache.redis.max-pool-size` / `min-pool-size` | `8` / `0` | Connection-pool bounds. |
| `firefly.cache.redis.ssl` | `false` | Enable TLS (`rediss://`). |
| `firefly.cache.redis.enable-keyspace-notifications` | `false` | Enable Redis keyspace/expiry notifications. |

> Auto-configuration is conditional: `RedisCacheAutoConfiguration` only contributes the connection factory and template when `ReactiveRedisTemplate` is on the classpath, `firefly.cache.redis.enabled` is `true`, and a non-empty `firefly.cache.redis.host` is set. Both the `ReactiveRedisConnectionFactory` and `ReactiveRedisTemplate` beans are `@ConditionalOnMissingBean` — define your own to override serialization, clustering, or connection behaviour.

## Documentation

- Firefly Framework module catalog and architecture docs: [github.com/fireflyframework/.github](https://github.com/fireflyframework/.github/blob/main/profile/MODULE_CATALOG.md)
- Cache SPI and core concepts: [`fireflyframework-cache`](https://github.com/fireflyframework/fireflyframework-cache)

## Contributing

Contributions are welcome. Please read the [CONTRIBUTING.md](CONTRIBUTING.md) guide for details on our code of conduct, development process, and how to submit pull requests.

## License

Copyright 2024-2026 Firefly Software Foundation.

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.
