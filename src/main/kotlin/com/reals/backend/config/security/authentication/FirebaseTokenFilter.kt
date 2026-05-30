package com.reals.backend.config.security.authentication

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.reals.backend.config.security.SecurityRoles
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
                code = "MISSING_AUTHORIZATION_HEADER",
                message = "Missing Authorization header"
            )
            return
        }

        val token = authHeader.removePrefix("Bearer ").trim()
        if (token.isBlank()) {
            writeUnauthorized(
                response = response,
                code = "MISSING_BEARER_TOKEN",
                message = "Missing bearer token"
            )
            return
        }

        try {
            val decoded = FirebaseAuth.getInstance()
                .verifyIdToken(token)

            val user = userService.findByFirebaseUid(decoded.uid)

            SecurityContextHolder.getContext().authentication =
                if (user == null) {
                    UsernamePasswordAuthenticationToken(
                        FirebasePrincipal(
                            uid = decoded.uid,
                            email = decoded.email
                        ),
                        null,
                        listOf(SimpleGrantedAuthority(SecurityRoles.ROLE_FIREBASE_AUTHENTICATED))
                    )
                } else {
                    UsernamePasswordAuthenticationToken(
                        user.id.toString(),
                        null,
                        listOf(SimpleGrantedAuthority(SecurityRoles.ROLE_USER))
                    )
                }
        } catch (ex: FirebaseAuthException) {
            log.debug("Firebase token rejected: ${ex.message}")
            SecurityContextHolder.clearContext()
            writeUnauthorized(
                response = response,
                code = "INVALID_TOKEN",
                message = "Invalid or expired token"
            )
            return
        }

        filterChain.doFilter(request, response)
    }

    private fun writeUnauthorized(
        response: HttpServletResponse,
        code: String,
        message: String
    ) {
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(
            """{"code":"$code","error":"Unauthorized","message":"$message"}"""
        )
    }
}
