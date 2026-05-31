# Firefly Framework - Cache Redis

[![CI](https://github.com/fireflyframework/fireflyframework-cache-redis/actions/workflows/ci.yml/badge.svg)](https://github.com/fireflyframework/fireflyframework-cache-redis/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://openjdk.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green.svg)](https://spring.io/projects/spring-boot)

> Redis provider adapter for the Firefly Framework reactive cache abstraction — a non-blocking, distributed `CacheAdapter` backed by Spring Data Redis and the Lettuce reactive driver.

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

`fireflyframework-cache-redis` is a **pluggable provider adapter** for the Firefly Framework cache abstraction. It supplies a Redis-backed implementation of the `CacheAdapter` SPI defined in [`fireflyframework-cache-core`](https://github.com/fireflyframework/fireflyframework-cache-core), so application code depends only on the core abstraction while the actual cache lives in Redis.

The core module provides an in-process Caffeine cache by default. When you need a **distributed cache shared across multiple service instances** — for example to keep cache entries consistent behind a load balancer, or to survive individual pod restarts — you add this adapter and select it. The adapter is built on `spring-boot-starter-data-redis-reactive`, using a `ReactiveRedisTemplate` so every operation (`get`, `put`, `evict`, `clear`, `stats`) is fully non-blocking and returns Project Reactor types, consistent with the rest of the framework.

Within the Firefly cache family, this is one of four provider adapters that plug into the same core SPI. The active provider is chosen with the `firefly.cache.provider` property in the core module, and this adapter additionally activates only when `firefly.cache.redis.enabled=true`:

- [`fireflyframework-cache-core`](https://github.com/fireflyframework/fireflyframework-cache-core) — the SPI (`CacheAdapter`, `CacheManager`, `CacheStats`) plus the default Caffeine provider
- **`fireflyframework-cache-redis`** — this module: distributed Redis provider
- [`fireflyframework-cache-hazelcast`](https://github.com/fireflyframework/fireflyframework-cache-hazelcast) — in-memory data grid provider
- [`fireflyframework-cache-jcache`](https://github.com/fireflyframework/fireflyframework-cache-jcache) — JSR-107 (JCache) provider
- [`fireflyframework-cache-postgresql`](https://github.com/fireflyframework/fireflyframework-cache-postgresql) — SQL-backed provider over PostgreSQL

## Features

- **Reactive, non-blocking** — implements the core `CacheAdapter` SPI on top of `ReactiveRedisTemplate`; no blocking calls on the request path.
- **Distributed by design** — a shared Redis server gives a single, consistent cache across all instances of a service.
- **Per-entry and default TTL** — `put(key, value, ttl)` honours an explicit time-to-live; when none is given it falls back to a configurable default (`firefly.cache.redis.default-ttl`).
- **Namespaced keys** — every key is prefixed (default `firefly:cache:`) so the framework's entries are isolated from other Redis data and easy to scan or flush.
- **JSON value serialization** — values are serialized with `Jackson2JsonRedisSerializer` and keys with `StringRedisSerializer`, producing human-readable, language-neutral entries.
- **Hit/miss statistics** — exposes `CacheStats` (hits, misses) via the `stats()` operation for observability.
- **Zero-code activation** — Spring Boot auto-configuration (`RedisCacheAutoConfiguration`) wires the `ReactiveRedisTemplate` and `RedisCacheAdapter` beans automatically when enabled.
- **Override-friendly** — all beans are `@ConditionalOnMissingBean`, so you can supply your own `ReactiveRedisTemplate` or `CacheAdapter` to customise serialization or connection behaviour.

## Requirements

- Java 21+ (Java 25 recommended)
- Spring Boot 3.x
- Maven 3.9+
- A reachable **Redis** server (Redis 6+; a reactive connection factory is auto-detected from your `spring.data.redis.*` settings)

## Installation

Add the adapter alongside the cache core. The version is managed by the Firefly parent/BOM, so no explicit `<version>` is required.

```xml
<dependency>
    <groupId>org.fireflyframework</groupId>
    <artifactId>fireflyframework-cache-redis</artifactId>
    <!-- version managed by fireflyframework-parent / BOM -->
</dependency>
```

This adapter declares `fireflyframework-cache-core` as a transitive dependency, so adding it brings the core SPI with it. Inherit the Firefly parent to get managed versions:

```xml
<parent>
    <groupId>org.fireflyframework</groupId>
    <artifactId>fireflyframework-parent</artifactId>
    <version>26.05.01</version>
</parent>
```

## Quick Start

**1. Add the dependency** (see [Installation](#installation)).

**2. Select Redis as the cache provider and enable the adapter** in `application.yml`:

```yaml
firefly:
  cache:
    provider: redis          # core selects the active provider
    redis:
      enabled: true          # this adapter activates only when true
      key-prefix: "firefly:cache:"
      default-ttl: 10m

spring:
  data:
    redis:
      host: localhost
      port: 6379
```

**3. Inject and use the cache** through the core abstraction — your code never references Redis directly:

```java
import org.fireflyframework.cache.CacheAdapter;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Service
public class ProductService {

    private final CacheAdapter cache;

    public ProductService(CacheAdapter cache) {
        this.cache = cache;
    }

    public Mono<Product> getProduct(String id) {
        return cache.get(id, Product.class)
                .switchIfEmpty(loadFromDatabase(id)
                        .flatMap(p -> cache.put(id, p, Duration.ofMinutes(30))
                                .thenReturn(p)));
    }

    public Mono<Void> invalidate(String id) {
        return cache.evict(id);
    }
}
```

Swapping to a different provider later is a configuration change only: point `firefly.cache.provider` at another adapter and add that adapter's dependency — application code is untouched.

## Configuration

All properties live under the `firefly.cache.redis.*` prefix and are bound by `RedisCacheProperties`. The provider selection (`firefly.cache.provider`) and global enable flag (`firefly.cache.enabled`) belong to the core module.

```yaml
firefly:
  cache:
    enabled: true            # core: master switch for the cache abstraction (default: true)
    provider: redis          # core: active provider (default: caffeine)
    redis:
      enabled: false         # adapter master switch (default: false)
      key-prefix: "firefly:cache:"   # prefix applied to every key (default: firefly:cache:)
      default-ttl: 10m       # TTL used when put() is called without an explicit TTL (default: 10m)
```

| Property | Default | Description |
| --- | --- | --- |
| `firefly.cache.redis.enabled` | `false` | Activates `RedisCacheAutoConfiguration`. Must be `true` to register the Redis `CacheAdapter`. |
| `firefly.cache.redis.key-prefix` | `firefly:cache:` | Prefix prepended to all keys; isolates framework entries and scopes the `clear()` scan/flush. |
| `firefly.cache.redis.default-ttl` | `10m` | Default time-to-live applied when `put(key, value, ttl)` is invoked with a null TTL. Accepts Spring `Duration` syntax (`10m`, `30s`, `1h`). |

**Redis connection** itself is configured with standard Spring Boot properties (`spring.data.redis.host`, `spring.data.redis.port`, `spring.data.redis.password`, etc.). The adapter consumes whatever `ReactiveRedisConnectionFactory` Spring Boot auto-detects.

> The auto-configuration is conditional: it only takes effect when `firefly.cache.redis.enabled=true` **and** `ReactiveRedisTemplate` is on the classpath. The `ReactiveRedisTemplate` and `RedisCacheAdapter` beans are `@ConditionalOnMissingBean`, so define your own to override serialization or wiring.

## Documentation

- Firefly Framework module catalog and architecture docs: [github.com/fireflyframework/.github](https://github.com/fireflyframework/.github/blob/main/profile/MODULE_CATALOG.md)
- Cache SPI and core concepts: [`fireflyframework-cache-core`](https://github.com/fireflyframework/fireflyframework-cache-core)

## Contributing

Contributions are welcome. Please read the [CONTRIBUTING.md](CONTRIBUTING.md) guide for details on our code of conduct, development process, and how to submit pull requests.

## License

Copyright 2024-2026 Firefly Software Foundation.

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.
