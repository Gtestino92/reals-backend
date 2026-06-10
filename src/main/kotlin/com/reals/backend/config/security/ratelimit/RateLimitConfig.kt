package com.reals.backend.config.security.ratelimit

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(RateLimitProperties::class)
class RateLimitConfig
