# Firefly Framework - Cache - Redis

[![CI](https://github.com/fireflyframework/fireflyframework-cache-redis/actions/workflows/ci.yml/badge.svg)](https://github.com/fireflyframework/fireflyframework-cache-redis/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://openjdk.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green.svg)](https://spring.io/projects/spring-boot)

> Redis distributed cache provider for the Firefly Framework cache abstraction, backed by Spring Data Redis and Lettuce.

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

Firefly Framework Cache Redis implements the `CacheAdapter` port of the `fireflyframework-cache` abstraction using Redis as a distributed cache backend. It plugs into the cache `CacheProviderFactory` SPI via `RedisProvider`, so simply placing this adapter on the classpath makes the `REDIS` cache type available to `FireflyCacheManager`.

The module ships `RedisCacheAdapter`, a fully reactive adapter built on `ReactiveRedisTemplate`, configured through `RedisCacheConfig`. Redis infrastructure beans (`ReactiveRedisConnectionFactory`, `ReactiveRedisTemplate`) are provided by `RedisCacheAutoConfiguration` when a Redis host is configured, and adapters are created on demand by the core `CacheManagerFactory` through `RedisCacheHelper`.

## Features

- Reactive `CacheAdapter` implementation backed by Redis and Lettuce
- SPI registration via `RedisProvider` for the `REDIS` cache type
- Spring Boot auto-configuration via `RedisCacheAutoConfiguration`
- Connection factory and reactive template wiring from Firefly cache properties
- Key prefixing, per-cache TTL, and namespaced keys
- JSON value serialization via `GenericJackson2JsonRedisSerializer`
- Health and statistics reporting through the cache abstraction
- Works standalone or as the L2 tier in a smart multi-tier cache

## Requirements

- Java 21+
- Spring Boot 3.x
- Maven 3.9+
- Redis server instance

## Installation

```xml
<dependency>
    <groupId>org.fireflyframework</groupId>
    <artifactId>fireflyframework-cache-redis</artifactId>
    <version>26.05.07</version>
</dependency>
```

## Quick Start

```xml
<dependencies>
    <dependency>
        <groupId>org.fireflyframework</groupId>
        <artifactId>fireflyframework-cache</artifactId>
    </dependency>
    <dependency>
        <groupId>org.fireflyframework</groupId>
        <artifactId>fireflyframework-cache-redis</artifactId>
    </dependency>
</dependencies>
```

## Configuration

```yaml
firefly:
  cache:
    enabled: true
    default-cache-type: REDIS
    redis:
      enabled: true
      host: localhost
      port: 6379
      database: 0
      username: default
      password: my-secret
      key-prefix: firefly:cache
      default-ttl: PT30M
      connection-timeout: PT10S
      command-timeout: PT5S
      max-pool-size: 8
      min-pool-size: 0
      ssl: false
```

## Documentation

No additional documentation available for this project.

## Contributing

Contributions are welcome. Please read the [CONTRIBUTING.md](CONTRIBUTING.md) guide for details on our code of conduct, development process, and how to submit pull requests.

## License

Copyright 2024-2026 Firefly Software Foundation.

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.
