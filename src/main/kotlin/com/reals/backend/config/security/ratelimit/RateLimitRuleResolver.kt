package com.reals.backend.config.security.ratelimit

import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component

@Component
class RateLimitRuleResolver(
    private val properties: RateLimitProperties
) {

    fun resolve(request: HttpServletRequest): RateLimitRule {
        val group = resolveGroup(request)

        return when (group) {
            RateLimitGroup.PROVISION ->
                RateLimitRule(
                    id = group.id,
                    capacity = properties.provisionCapacity,
                    refillTokens = properties.provisionRefillTokens,
                    refillPeriodSeconds = properties.provisionRefillPeriodSeconds
                )

            RateLimitGroup.MESSAGES ->
                RateLimitRule(
                    id = group.id,
                    capacity = properties.messageCapacity,
                    refillTokens = properties.messageRefillTokens,
                    refillPeriodSeconds = properties.messageRefillPeriodSeconds
                )

            RateLimitGroup.PROFILE_PHOTO_UPLOADS ->
                RateLimitRule(
                    id = group.id,
                    capacity = properties.profilePhotoCapacity,
                    refillTokens = properties.profilePhotoRefillTokens,
                    refillPeriodSeconds = properties.profilePhotoRefillPeriodSeconds
                )

            RateLimitGroup.SAFETY_REPORTS ->
                RateLimitRule(
                    id = group.id,
                    capacity = properties.safetyReportCapacity,
                    refillTokens = properties.safetyReportRefillTokens,
                    refillPeriodSeconds = properties.safetyReportRefillPeriodSeconds
                )

            RateLimitGroup.DEFAULT ->
                RateLimitRule(
                    id = group.id,
                    capacity = properties.defaultCapacity,
                    refillTokens = properties.defaultRefillTokens,
                    refillPeriodSeconds = properties.defaultRefillPeriodSeconds
                )
        }
    }

    fun resolveGroup(request: HttpServletRequest): RateLimitGroup {
        val path = request.normalizedPath()
        val method = request.method.uppercase()

        return when {
            method == "POST" && path == "/api/me/provision" ->
                RateLimitGroup.PROVISION

            method == "POST" &&
                path.startsWith("/api/chats/") &&
                (path.endsWith("/messages") || path.endsWith("/audio-messages")) ->
                RateLimitGroup.MESSAGES

            method == "POST" && path == "/api/me/profile/photos" ->
                RateLimitGroup.PROFILE_PHOTO_UPLOADS

            method == "PUT" && PROFILE_PHOTO_REPLACEMENT_PATH.matches(path) ->
                RateLimitGroup.PROFILE_PHOTO_UPLOADS

            method == "POST" && path == "/api/safety/reports" ->
                RateLimitGroup.SAFETY_REPORTS

            else -> RateLimitGroup.DEFAULT
        }
    }
}

data class RateLimitRule(
    val id: String,
    val capacity: Int,
    val refillTokens: Int,
    val refillPeriodSeconds: Long
)

enum class RateLimitGroup(
    val id: String
) {
    DEFAULT("default"),
    PROVISION("provision"),
    MESSAGES("messages"),
    PROFILE_PHOTO_UPLOADS("profile-photo-uploads"),
    SAFETY_REPORTS("safety-reports")
}

fun HttpServletRequest.normalizedPath(): String =
    servletPath.ifBlank {
        requestURI.removePrefix(contextPath)
    }

private val PROFILE_PHOTO_REPLACEMENT_PATH = Regex("^/api/me/profile/photos/[^/]+/file$")
