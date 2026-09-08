package com.reals.backend.config.security.ratelimit

import com.github.benmanes.caffeine.cache.Caffeine
import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.reals.backend.config.security.authentication.FirebasePrincipal
import com.reals.backend.config.security.currentuser.CurrentUserAuthContext
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

@Component
class PostAuthenticationRateLimitFilter(
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
            !path.startsWith("/api/") ||
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
        val principalIdentity = principalIdentity()
        if (principalIdentity == null) {
            filterChain.doFilter(request, response)
            return
        }

        val now = Instant.now()
        val rule = ruleResolver.resolve(request)
        val bucketKey = rateLimitKey(rule, principalIdentity)
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
                writeRateLimitExceeded(response, decision.retryAfterSeconds)
            }
        }
    }

    internal fun rateLimitKey(
        rule: RateLimitRule,
        principalIdentity: PrincipalRateLimitIdentity
    ): String =
        "post-auth:${rule.id}:${principalIdentity.type}:${principalIdentity.stableId}"

    internal fun principalIdentity(): PrincipalRateLimitIdentity? {
        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication?.isAuthenticated != true) {
            return null
        }

        return when (val principal = authentication.principal) {
            is CurrentUserAuthContext -> PrincipalRateLimitIdentity("user", principal.userId.toString())
            is FirebasePrincipal -> PrincipalRateLimitIdentity("firebase", principal.uid)
            is String -> PrincipalRateLimitIdentity("local-dev", principal)
            else -> null
        }
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

data class PrincipalRateLimitIdentity(
    val type: String,
    val stableId: String
)
