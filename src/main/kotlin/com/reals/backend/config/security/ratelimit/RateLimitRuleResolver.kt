package com.reals.backend.config.security.ratelimit

import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component

@Component
class RateLimitRuleResolver(
    private val properties: RateLimitProperties
) {

    fun resolve(request: HttpServletRequest): RateLimitRule {
        val path = request.normalizedPath()
        val method = request.method.uppercase()

        return when {
            method == "POST" && path == "/api/me/provision" ->
                RateLimitRule(
                    id = "provision",
                    capacity = properties.provisionCapacity,
                    refillTokens = properties.provisionRefillTokens,
                    refillPeriodSeconds = properties.provisionRefillPeriodSeconds
                )

            method == "POST" &&
                path.startsWith("/api/chats/") &&
                path.endsWith("/messages") ->
                RateLimitRule(
                    id = "messages",
                    capacity = properties.messageCapacity,
                    refillTokens = properties.messageRefillTokens,
                    refillPeriodSeconds = properties.messageRefillPeriodSeconds
                )

            method in PROFILE_PHOTO_MUTATION_METHODS &&
                path.startsWith("/api/me/profile/photos") ->
                RateLimitRule(
                    id = "profile-photos",
                    capacity = properties.profilePhotoCapacity,
                    refillTokens = properties.profilePhotoRefillTokens,
                    refillPeriodSeconds = properties.profilePhotoRefillPeriodSeconds
                )

            method == "POST" && path == "/api/safety/reports" ->
                RateLimitRule(
                    id = "safety-reports",
                    capacity = properties.safetyReportCapacity,
                    refillTokens = properties.safetyReportRefillTokens,
                    refillPeriodSeconds = properties.safetyReportRefillPeriodSeconds
                )

            else ->
                RateLimitRule(
                    id = "default",
                    capacity = properties.defaultCapacity,
                    refillTokens = properties.defaultRefillTokens,
                    refillPeriodSeconds = properties.defaultRefillPeriodSeconds
                )
        }
    }
}

data class RateLimitRule(
    val id: String,
    val capacity: Int,
    val refillTokens: Int,
    val refillPeriodSeconds: Long
)

fun HttpServletRequest.normalizedPath(): String =
    servletPath.ifBlank {
        requestURI.removePrefix(contextPath)
    }

private val PROFILE_PHOTO_MUTATION_METHODS = setOf("POST", "PUT", "DELETE")
