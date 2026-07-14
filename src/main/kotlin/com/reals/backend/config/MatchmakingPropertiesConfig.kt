package com.reals.backend.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(MatchmakingProperties::class)
class MatchmakingPropertiesConfig
