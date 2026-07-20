package com.reals.backend.config.security.ratelimit

import com.github.benmanes.caffeine.cache.Caffeine
import com.reals.backend.config.environment.EnvironmentExposurePolicy
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

@Component
class RateLimitFilter(
    private val properties: RateLimitProperties,
    private val ruleResolver: RateLimitRuleResolver,
    private val environmentExposurePolicy: EnvironmentExposurePolicy
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
            !path.isPreAuthRateLimitedPath() ||
            path == "/api/ping" ||
            (
                request.method.equals("GET", ignoreCase = true) &&
                    path == "/api/legal/documents/current"
            ) ||
            (
                environmentExposurePolicy.localDevEndpointsAllowed() &&
                    path.startsWith("/api/local-dev/")
            )
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val now = Instant.now()
        val rule = preAuthenticationRule(request)
        val bucketKey = rateLimitKey(request, rule)
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

    internal fun rateLimitKey(request: HttpServletRequest, rule: RateLimitRule = preAuthenticationRule(request)): String =
        "pre-auth:${rule.id}:ip:${request.remoteAddr ?: "unknown"}"

    internal fun preAuthenticationRule(request: HttpServletRequest): RateLimitRule {
        val group = ruleResolver.resolveGroup(request)
        return RateLimitRule(
            id = group.id,
            capacity = properties.preAuthCapacity,
            refillTokens = properties.preAuthRefillTokens,
            refillPeriodSeconds = properties.preAuthRefillPeriodSeconds
        )
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

    private fun String.isPreAuthRateLimitedPath(): Boolean =
        startsWith("/api/") ||
            this == "/actuator/info" ||
            this == "/actuator/metrics" ||
            startsWith("/actuator/metrics/")
}
