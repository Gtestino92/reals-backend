package com.reals.backend.config.security

import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.reals.backend.config.security.authentication.DevAutoAuthFilter
import com.reals.backend.config.security.authentication.FirebaseTokenFilter
import com.reals.backend.config.security.ratelimit.PostAuthenticationRateLimitFilter
import com.reals.backend.config.security.ratelimit.RateLimitFilter
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val environmentExposurePolicy: EnvironmentExposurePolicy,
    private val devAutoAuthFilter: DevAutoAuthFilter?,
    private val firebaseTokenFilter: FirebaseTokenFilter?,
    private val rateLimitFilter: RateLimitFilter?,
    private val postAuthenticationRateLimitFilter: PostAuthenticationRateLimitFilter?
) {

    @Bean
    fun securityFilterChain(
        http: HttpSecurity
    ): SecurityFilterChain {
        http
            // codeql[java/spring-disabled-csrf-protection]
            // Reals is a stateless API using Authorization bearer tokens, not cookie-based browser sessions.
            // Revisit before adding cookie auth, form login, browser sessions, or browser-attached credentials.
            .csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .sessionManagement {
                it.sessionCreationPolicy(
                    SessionCreationPolicy.STATELESS
                )
            }
            .exceptionHandling { exceptions ->
                exceptions
                    .authenticationEntryPoint { _, response, _ ->
                        writeSecurityError(
                            response = response,
                            status = HttpServletResponse.SC_UNAUTHORIZED,
                            code = "AUTHENTICATION_REQUIRED",
                            error = "Unauthorized",
                            message = "Authentication is required"
                        )
                    }
                    .accessDeniedHandler { _, response, _ ->
                        writeSecurityError(
                            response = response,
                            status = HttpServletResponse.SC_FORBIDDEN,
                            code = "ACCESS_DENIED",
                            error = "Forbidden",
                            message = "Access is denied"
                        )
                    }
            }
            // Allow H2 console frames (only active on local-nodb profile)
            .headers { headers ->
                headers.addHeaderWriter(
                    XFrameOptionsHeaderWriter(
                        XFrameOptionsHeaderWriter.XFrameOptionsMode.SAMEORIGIN
                    )
                )
            }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers("/api/ping").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/legal/documents/current").permitAll()
                    .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    .requestMatchers(
                        "/actuator/info",
                        "/actuator/metrics",
                        "/actuator/metrics/**"
                    ).hasRole(SecurityRoles.ADMIN)

                when {
                    environmentExposurePolicy.localDevEndpointsAllowed() -> {
                        // Local tooling executes system jobs; bearer auth only adds local friction.
                        auth.requestMatchers("/api/local-dev/**").permitAll()
                    }
                    environmentExposurePolicy.devAdminToolingAllowed() -> {
                        auth.requestMatchers("/api/local-dev/**").hasRole(SecurityRoles.ADMIN)
                    }
                    else -> {
                        auth.requestMatchers("/api/local-dev/**").denyAll()
                    }
                }

                if (environmentExposurePolicy.h2ConsoleAllowed()) {
                    auth.requestMatchers("/h2-console/**").permitAll()
                } else {
                    auth.requestMatchers("/h2-console/**").denyAll()
                }

                auth
                    .requestMatchers(HttpMethod.POST, "/api/me/provision")
                    .hasAnyRole(SecurityRoles.FIREBASE_AUTHENTICATED, SecurityRoles.USER)
                    .requestMatchers("/api/admin/**").hasRole(SecurityRoles.ADMIN)
                    .requestMatchers("/api/**").hasRole(SecurityRoles.USER)
                    .anyRequest().denyAll()
            }

        rateLimitFilter?.let {
            http.addFilterBefore(it, UsernamePasswordAuthenticationFilter::class.java)
        }

        firebaseTokenFilter?.let {
            if (rateLimitFilter != null) {
                http.addFilterAfter(it, RateLimitFilter::class.java)
            } else {
                http.addFilterBefore(it, UsernamePasswordAuthenticationFilter::class.java)
            }
        }

        devAutoAuthFilter?.let {
            if (rateLimitFilter != null) {
                http.addFilterAfter(it, RateLimitFilter::class.java)
            } else {
                http.addFilterBefore(it, UsernamePasswordAuthenticationFilter::class.java)
            }
        }

        postAuthenticationRateLimitFilter?.let {
            when {
                firebaseTokenFilter != null -> http.addFilterAfter(it, FirebaseTokenFilter::class.java)
                devAutoAuthFilter != null -> http.addFilterAfter(it, DevAutoAuthFilter::class.java)
                rateLimitFilter != null -> http.addFilterAfter(it, RateLimitFilter::class.java)
                else -> http.addFilterAfter(it, UsernamePasswordAuthenticationFilter::class.java)
            }
        }

        return http.build()
    }

    private fun writeSecurityError(
        response: HttpServletResponse,
        status: Int,
        code: String,
        error: String,
        message: String
    ) {
        response.status = status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(
            """{"code":"$code","error":"$error","message":"$message"}"""
        )
    }
}
