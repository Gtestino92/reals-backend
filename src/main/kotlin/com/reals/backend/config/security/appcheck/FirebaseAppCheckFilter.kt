package com.reals.backend.config.security.appcheck

import com.reals.backend.config.security.ratelimit.normalizedPath
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter

class FirebaseAppCheckFilter(
    private val properties: FirebaseAppCheckProperties,
    private val verifier: FirebaseAppCheckVerifier,
    private val meterRegistry: MeterRegistry?
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        if (properties.mode == FirebaseAppCheckMode.DISABLED) {
            return true
        }

        val path = request.normalizedPath()

        return request.method.equals("OPTIONS", ignoreCase = true) ||
            !path.startsWith("/api/") ||
            path == "/api/ping" ||
            path.startsWith("/api/local-dev/") ||
            path == "/actuator/health" ||
            path.startsWith("/actuator/health/") ||
            path == "/actuator/info" ||
            path == "/actuator/metrics" ||
            path.startsWith("/actuator/metrics/") ||
            path.startsWith("/h2-console/")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val token = request.getHeader(HEADER_NAME)?.trim().orEmpty()

        if (token.isBlank()) {
            record(request, "missing", null)
            if (properties.mode == FirebaseAppCheckMode.ENFORCED) {
                writeError(
                    response = response,
                    status = HttpServletResponse.SC_UNAUTHORIZED,
                    code = "MISSING_APP_CHECK_TOKEN",
                    error = "Unauthorized",
                    message = "Missing Firebase App Check token"
                )
                return
            }

            filterChain.doFilter(request, response)
            return
        }

        when (val result = verifier.verify(token)) {
            is FirebaseAppCheckVerificationResult.Valid -> {
                request.setAttribute(VALID_APP_ID_ATTRIBUTE, result.appId)
                record(request, "valid", null)
                filterChain.doFilter(request, response)
            }

            FirebaseAppCheckVerificationResult.Invalid -> {
                record(request, "invalid", null)
                if (properties.mode == FirebaseAppCheckMode.ENFORCED) {
                    writeError(
                        response = response,
                        status = HttpServletResponse.SC_UNAUTHORIZED,
                        code = "INVALID_APP_CHECK_TOKEN",
                        error = "Unauthorized",
                        message = "Invalid Firebase App Check token"
                    )
                    return
                }

                filterChain.doFilter(request, response)
            }

            is FirebaseAppCheckVerificationResult.Unavailable -> {
                record(request, "unavailable", result.exceptionClass)
                log.warn(
                    "Firebase App Check verification unavailable mode={} endpointGroup={} exceptionClass={}",
                    properties.mode,
                    endpointGroup(request),
                    result.exceptionClass
                )
                if (properties.mode == FirebaseAppCheckMode.ENFORCED) {
                    writeError(
                        response = response,
                        status = HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        code = "APP_CHECK_VERIFICATION_UNAVAILABLE",
                        error = "Service Unavailable",
                        message = "Firebase App Check verification is temporarily unavailable"
                    )
                    return
                }

                filterChain.doFilter(request, response)
            }
        }
    }

    private fun record(
        request: HttpServletRequest,
        outcome: String,
        exceptionClass: String?
    ) {
        meterRegistry?.let {
            Counter.builder(METER_NAME)
                .tag("mode", properties.mode.name.lowercase())
                .tag("outcome", outcome)
                .tag("endpoint_group", endpointGroup(request))
                .tag("exception", exceptionClass ?: "none")
                .register(it)
                .increment()
        }
    }

    private fun endpointGroup(request: HttpServletRequest): String =
        when {
            request.normalizedPath() == "/api/me/provision" -> "provision"
            request.normalizedPath().startsWith("/api/me/profile/photos") -> "profile-photo"
            request.normalizedPath().startsWith("/api/legal/") -> "legal"
            request.normalizedPath().startsWith("/api/admin/") -> "admin"
            else -> "api"
        }

    private fun writeError(
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

    companion object {
        const val HEADER_NAME = "X-Firebase-AppCheck"
        const val VALID_APP_ID_ATTRIBUTE = "firebaseAppCheck.appId"
        const val METER_NAME = "reals.app_check.requests"
    }
}
