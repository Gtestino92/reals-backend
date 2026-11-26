package com.reals.backend.config.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.CorsUtils
import java.net.URI

internal class ApiCorsConfiguration(configuredOrigins: String) : CorsConfigurationSource {
    private val api = PathPatternRequestMatcher.withDefaults().matcher("/api/**")
    private val origins = parseOrigins(configuredOrigins)
    private val denyPreflight = CorsConfiguration()
    private val configuration = object : CorsConfiguration() {
        override fun checkHeaders(requestHeaders: List<String>?): List<String>? =
            super.checkHeaders(requestHeaders)?.takeIf { it.size == requestHeaders?.size }
    }.apply {
        allowedOrigins = origins.toList()
        allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        allowedHeaders = listOf("Authorization", "Content-Type", "X-Firebase-AppCheck", "X-Reals-Admin-Grant", "X-Contact-Revision")
        exposedHeaders = listOf("Retry-After", "X-RateLimit-Limit", "X-RateLimit-Remaining", "X-Reals-Counterpart-Audio-Access")
        allowCredentials = false
        maxAge = 3600L
    }

    override fun getCorsConfiguration(request: HttpServletRequest): CorsConfiguration? {
        val preflight = CorsUtils.isPreFlightRequest(request)
        if (api.matches(request) && request.getHeader(HttpHeaders.ORIGIN) in origins) {
            return configuration
        }
        // An unlisted origin gets no browser permission. Actual requests still use normal security,
        // including same-origin browser writes forwarded by the Vite proxy with a rewritten Host.
        return if (preflight) denyPreflight else null
    }

    fun writeVaryOrigin(request: HttpServletRequest, response: HttpServletResponse) {
        // Spring adds Vary for configured CORS responses. Actual API requests without a matching
        // configuration also need to vary by Origin so caches cannot reuse them for allowed origins.
        if (api.matches(request) && HttpHeaders.ORIGIN !in response.getHeaders(HttpHeaders.VARY)) {
            response.addHeader(HttpHeaders.VARY, HttpHeaders.ORIGIN)
        }
    }

    private fun parseOrigins(configuredOrigins: String): Set<String> {
        if (configuredOrigins.isBlank()) return emptySet()

        return configuredOrigins.split(',').map { entry ->
            val origin = entry.trim()
            val uri = runCatching { URI(origin) }.getOrNull()
            val valid = uri != null && uri.scheme == "https" && uri.host != null &&
                uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null &&
                (uri.rawAuthority == uri.host ||
                    (uri.port in 1..65535 && uri.rawAuthority == "${uri.host}:${uri.port}"))
            require(valid) { "REALS_CORS_ALLOWED_ORIGINS must be a comma-separated list of HTTPS origins (host and optional port)" }
            origin
        }.toSet()
    }
}
