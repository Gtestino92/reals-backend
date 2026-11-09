package com.reals.backend.config.security

import com.reals.backend.config.security.ratelimit.normalizedPath
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

/** Apply before early security rejections, as well as through Spring's response header writer. */
internal fun HttpServletRequest.preventAdministrativeCaching(response: HttpServletResponse) {
    val path = normalizedPath()
    if (path == "/api/admin-auth" || path.startsWith("/api/admin-auth/") ||
        path == "/api/admin" || path.startsWith("/api/admin/") ||
        path == "/actuator/info" || path == "/actuator/metrics" || path.startsWith("/actuator/metrics/") ||
        path.startsWith("/api/local-dev/") || getHeader("X-Reals-Admin-Grant") != null
    ) response.setHeader("Cache-Control", "no-store")
}
