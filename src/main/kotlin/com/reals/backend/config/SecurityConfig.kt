package com.reals.backend.config

import com.reals.backend.config.filter.DevAutoAuthFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val devAutoAuthFilter: DevAutoAuthFilter? // null in dev/prod if not on classpath/profile
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
                    .requestMatchers("/h2-console/**").permitAll()
                    .requestMatchers("/api/**").authenticated()
                    .anyRequest().denyAll()
            }

        // DEV only
        devAutoAuthFilter?.let {
            http.addFilterBefore(
                it,
                UsernamePasswordAuthenticationFilter::class.java
            )
        }

        // TODO(firebase):
        // add FirebaseTokenFilter here once dependency resolves

        return http.build()
    }
}
