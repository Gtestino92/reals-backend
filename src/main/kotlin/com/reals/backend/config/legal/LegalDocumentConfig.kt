package com.reals.backend.config.legal

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(LegalDocumentProperties::class)
class LegalDocumentConfig
