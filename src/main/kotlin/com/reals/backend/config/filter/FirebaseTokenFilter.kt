package com.reals.backend.config.filter

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.reals.backend.service.UserService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
@Profile("local-firebase", "dev", "prod")
class FirebaseTokenFilter(
    private val userService: UserService
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.servletPath
        return request.method.equals("OPTIONS", ignoreCase = true) ||
            path == "/api/ping" ||
            path.startsWith("/api/auth/") ||
            path.startsWith("/h2-console/")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val authHeader = request.getHeader("Authorization")

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            writeUnauthorized(
                response = response,
                message = "Missing Authorization header"
            )
            return
        }

        val token = authHeader.removePrefix("Bearer ").trim()
        if (token.isBlank()) {
            writeUnauthorized(
                response = response,
                message = "Missing bearer token"
            )
            return
        }

        try {
            val decoded = FirebaseAuth.getInstance()
                .verifyIdToken(token)

            val user = userService.findOrCreate(
                firebaseUid = decoded.uid,
                email = decoded.email
            )

            SecurityContextHolder.getContext().authentication =
                UsernamePasswordAuthenticationToken(
                    user.id.toString(),
                    null,
                    listOf(SimpleGrantedAuthority("ROLE_USER"))
                )
        } catch (ex: FirebaseAuthException) {
            log.debug("Firebase token rejected: ${ex.message}")
            SecurityContextHolder.clearContext()
            writeUnauthorized(
                response = response,
                message = "Invalid or expired token"
            )
            return
        }

        filterChain.doFilter(request, response)
    }

    private fun writeUnauthorized(
        response: HttpServletResponse,
        message: String
    ) {
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(
            """{"error":"Unauthorized","message":"$message"}"""
        )
    }
}
