package com.reals.backend.config.security.ratelimit

import com.github.benmanes.caffeine.cache.Caffeine
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.TimeUnit

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class RateLimitFilter(
    private val properties: RateLimitProperties
) : OncePerRequestFilter() {

    private val buckets = Caffeine.newBuilder()
        .maximumSize(100_000)
        .expireAfterAccess(15, TimeUnit.MINUTES)
        .build<String, TokenBucket>()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        if (!properties.enabled) {
            return true
        }

        val path = request.normalizedPath()

        return request.method.equals("OPTIONS", ignoreCase = true) ||
            !path.startsWith("/api/") ||
            path == "/api/ping"
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val now = Instant.now()
        val rule = resolveRule(request)
        val bucketKey = "${rule.id}:${request.clientIdentity()}"
        val bucket = buckets.get(bucketKey) { _ ->
            TokenBucket(
                capacity = rule.capacity,
                refillTokens = rule.refillTokens,
                refillPeriod = Duration.ofSeconds(rule.refillPeriodSeconds),
                now = now
            )
        }

        when (val decision = bucket.tryConsume(now)) {
            is RateLimitDecision.Allowed -> {
                response.setHeader("X-RateLimit-Limit", rule.capacity.toString())
                response.setHeader("X-RateLimit-Remaining", decision.remainingTokens.toString())
                filterChain.doFilter(request, response)
            }

            is RateLimitDecision.Rejected -> {
                writeRateLimitExceeded(
                    response = response,
                    retryAfterSeconds = decision.retryAfterSeconds
                )
            }
        }
    }

    private fun resolveRule(request: HttpServletRequest): RateLimitRule {
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

    private fun HttpServletRequest.clientIdentity(): String {
        val bearerToken = getHeader(HttpHeaders.AUTHORIZATION)
            ?.trim()
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(" ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        if (bearerToken != null) {
            return "bearer:${sha256Prefix(bearerToken)}"
        }

        return "ip:${remoteAddr ?: "unknown"}"
    }

    private fun HttpServletRequest.normalizedPath(): String =
        servletPath.ifBlank {
            requestURI.removePrefix(contextPath)
        }

    private fun sha256Prefix(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))

        return HexFormat.of().formatHex(digest).take(32)
    }

    private fun writeRateLimitExceeded(
        response: HttpServletResponse,
        retryAfterSeconds: Long
    ) {
        response.status = 429
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.setHeader(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString())
        response.writer.write(
            """{"code":"RATE_LIMIT_EXCEEDED","error":"Too Many Requests","message":"Too many requests. Try again later."}"""
        )
    }
}

private data class RateLimitRule(
    val id: String,
    val capacity: Int,
    val refillTokens: Int,
    val refillPeriodSeconds: Long
)

private val PROFILE_PHOTO_MUTATION_METHODS = setOf("POST", "PUT", "DELETE")
