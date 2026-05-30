package com.reals.backend.config.security

import com.reals.backend.config.security.authentication.DevAutoAuthFilter
import com.reals.backend.config.security.authentication.FirebaseTokenFilter
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
    private val devAutoAuthFilter: DevAutoAuthFilter?,
    private val firebaseTokenFilter: FirebaseTokenFilter?
) {

    @Bean
    fun securityFilterChain(
        http: HttpSecurity
    ): SecurityFilterChain {
        http
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
                    .requestMatchers("/api/auth/**").permitAll()
                    .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                    .requestMatchers("/h2-console/**").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/me/provision")
                    .hasAnyRole(SecurityRoles.FIREBASE_AUTHENTICATED, SecurityRoles.USER)
                    .requestMatchers("/api/**").hasRole(SecurityRoles.USER)
                    .anyRequest().denyAll()
            }

        devAutoAuthFilter?.let {
            http.addFilterBefore(
                it,
                UsernamePasswordAuthenticationFilter::class.java
            )
        }

        firebaseTokenFilter?.let {
            http.addFilterBefore(
                it,
                UsernamePasswordAuthenticationFilter::class.java
            )
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
