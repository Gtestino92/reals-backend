package com.reals.backend.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(MatchmakingJobProperties::class)
class SchedulerPropertiesConfig
