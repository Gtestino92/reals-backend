package com.reals.backend.config.security.ratelimit

import com.reals.backend.config.security.authentication.DevAutoAuthFilter
import com.reals.backend.config.security.authentication.FirebaseTokenFilter
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(RateLimitProperties::class)
class RateLimitConfig {

    @Bean
    fun rateLimitFilterRegistration(filter: RateLimitFilter): FilterRegistrationBean<RateLimitFilter> =
        FilterRegistrationBean(filter).apply {
            isEnabled = false
        }

    @Bean
    fun postAuthenticationRateLimitFilterRegistration(
        filter: PostAuthenticationRateLimitFilter
    ): FilterRegistrationBean<PostAuthenticationRateLimitFilter> =
        FilterRegistrationBean(filter).apply {
            isEnabled = false
        }

    @Bean
    @ConditionalOnBean(FirebaseTokenFilter::class)
    fun firebaseTokenFilterRegistration(filter: FirebaseTokenFilter): FilterRegistrationBean<FirebaseTokenFilter> =
        FilterRegistrationBean(filter).apply {
            isEnabled = false
        }

    @Bean
    @ConditionalOnBean(DevAutoAuthFilter::class)
    fun devAutoAuthFilterRegistration(filter: DevAutoAuthFilter): FilterRegistrationBean<DevAutoAuthFilter> =
        FilterRegistrationBean(filter).apply {
            isEnabled = false
        }
}
