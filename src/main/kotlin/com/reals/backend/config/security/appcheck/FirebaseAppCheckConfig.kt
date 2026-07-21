package com.reals.backend.config.security.appcheck

import com.reals.backend.config.environment.EnvironmentExposurePolicy
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder

@Configuration
@EnableConfigurationProperties(FirebaseAppCheckProperties::class)
class FirebaseAppCheckConfig {

    @Bean
    @Profile("local-firebase", "dev", "prod")
    fun firebaseAppCheckVerifier(
        properties: FirebaseAppCheckProperties
    ): FirebaseAppCheckVerifier =
        NimbusFirebaseAppCheckVerifier(
            decoderFactory = LazyRemoteJwksJwtDecoderFactory(properties),
            properties = properties
        )

    @Bean
    @Profile("local-firebase", "dev", "prod")
    fun firebaseAppCheckFilter(
        properties: FirebaseAppCheckProperties,
        verifier: FirebaseAppCheckVerifier,
        meterRegistryProvider: ObjectProvider<MeterRegistry>
    ): FirebaseAppCheckFilter =
        FirebaseAppCheckFilter(properties, verifier, meterRegistryProvider.getIfAvailable())

    @Bean
    @ConditionalOnBean(FirebaseAppCheckFilter::class)
    fun firebaseAppCheckFilterRegistration(
        filter: FirebaseAppCheckFilter
    ): FilterRegistrationBean<FirebaseAppCheckFilter> =
        FilterRegistrationBean(filter).apply {
            isEnabled = false
        }

    @Bean
    @Profile("prod")
    fun firebaseAppCheckProductionStartupValidator(
        properties: FirebaseAppCheckProperties,
        environmentExposurePolicy: EnvironmentExposurePolicy
    ): FirebaseAppCheckProductionStartupValidator =
        FirebaseAppCheckProductionStartupValidator(properties, environmentExposurePolicy)
}

private class LazyRemoteJwksJwtDecoderFactory(
    private val properties: FirebaseAppCheckProperties
) : FirebaseAppCheckJwtDecoderFactory {

    @Volatile
    private var cachedDecoder: NimbusJwtDecoder? = null

    override fun decoder(): NimbusJwtDecoder =
        cachedDecoder ?: synchronized(this) {
            cachedDecoder ?: buildDecoder().also {
                cachedDecoder = it
            }
        }

    private fun buildDecoder(): NimbusJwtDecoder =
        NimbusJwtDecoder
            .withJwkSetUri(properties.parsedJwksUri().toString())
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .build()
}

class FirebaseAppCheckProductionStartupValidator(
    private val properties: FirebaseAppCheckProperties,
    private val environmentExposurePolicy: EnvironmentExposurePolicy
) {

    init {
        validate()
    }

    private fun validate() {
        if (!environmentExposurePolicy.isProduction()) {
            return
        }

        require(properties.mode == FirebaseAppCheckMode.ENFORCED) {
            "security.app-check.mode must be ENFORCED in prod"
        }

        require(PROJECT_NUMBER.matches(properties.normalizedProjectNumber())) {
            "security.app-check.project-number must be a numeric Firebase project number in prod"
        }

        require(properties.normalizedAllowedAppIds().isNotEmpty()) {
            "security.app-check.allowed-app-ids must contain at least one Firebase App ID in prod"
        }

        val uri = runCatching { properties.parsedJwksUri() }
            .getOrElse {
                throw IllegalStateException("security.app-check.jwks-uri must be a valid URI in prod")
            }

        require(uri.isAbsolute && !uri.scheme.isNullOrBlank() && !uri.host.isNullOrBlank()) {
            "security.app-check.jwks-uri must be an absolute URI with host in prod"
        }
    }

    private companion object {
        val PROJECT_NUMBER = Regex("^[0-9]+$")
    }
}
