package com.reals.backend.config.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestCorrelationIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val requestId = resolveRequestId(request)

        response.setHeader(REQUEST_ID_HEADER, requestId)
        MDC.put(MDC_KEY, requestId)

        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    private fun resolveRequestId(request: HttpServletRequest): String {
        val incoming = request.getHeader(REQUEST_ID_HEADER)
            ?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= MAX_REQUEST_ID_LENGTH }

        return incoming ?: UUID.randomUUID().toString()
    }

    companion object {
        const val REQUEST_ID_HEADER = "X-Request-Id"
        const val MDC_KEY = "requestId"
        private const val MAX_REQUEST_ID_LENGTH = 100
    }
}
