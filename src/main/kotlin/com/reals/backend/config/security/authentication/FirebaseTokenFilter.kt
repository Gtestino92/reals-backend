package com.reals.backend.config.security.authentication

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.reals.backend.config.security.SecurityRoles
import com.reals.backend.domain.UserStatus
import com.reals.backend.service.UserService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
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
    private val userService: UserService,
    @param:Value("\${backoffice.admin-emails:}")
    private val adminEmailsProperty: String = ""
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)
    private val adminEmails: Set<String> =
        adminEmailsProperty
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.servletPath.ifBlank {
            request.requestURI.removePrefix(request.contextPath)
        }
        return request.method.equals("OPTIONS", ignoreCase = true) ||
            path == "/api/ping" ||
            path.startsWith("/api/auth/") ||
            path.startsWith("/api/local-dev/") ||
            path == "/actuator/health" ||
            path.startsWith("/actuator/health/") ||
            path == "/actuator/info" ||
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
                .verifyIdToken(token, true)

            val user = userService.findByFirebaseUid(decoded.uid)

            if (user?.status == UserStatus.DELETED) {
                if (!isDeletedAccountAllowedPath(request)) {
                    SecurityContextHolder.clearContext()
                    writeUnauthorized(
                        response = response,
                        code = "ACCOUNT_DELETED",
                        message = "Account is pending deletion"
                    )
                    return
                }

                SecurityContextHolder.getContext().authentication =
                    UsernamePasswordAuthenticationToken(
                        user.id.toString(),
                        null,
                        listOf(SimpleGrantedAuthority(SecurityRoles.ROLE_USER))
                    )

                filterChain.doFilter(request, response)
                return
            }

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
                        authoritiesForActiveUser(
                            localEmail = user.email,
                            firebaseEmail = decoded.email
                        )
                    )
                }
        } catch (ex: FirebaseAuthException) {
            log.debug(
                "Firebase token rejected code={}",
                ex.authErrorCode?.name ?: ex.errorCode?.name ?: ex.javaClass.simpleName
            )
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

    private fun isDeletedAccountAllowedPath(request: HttpServletRequest): Boolean {
        val path = request.servletPath.ifBlank {
            request.requestURI.removePrefix(request.contextPath)
        }

        return (
            request.method.equals("GET", ignoreCase = true) &&
                path == "/api/me"
        ) || (
            request.method.equals("POST", ignoreCase = true) &&
            path == "/api/me/reactivation"
        )
    }

    private fun authoritiesForActiveUser(
        localEmail: String?,
        firebaseEmail: String?
    ): List<SimpleGrantedAuthority> {
        val authorities =
            mutableListOf(SimpleGrantedAuthority(SecurityRoles.ROLE_USER))

        val candidateEmails =
            listOfNotNull(firebaseEmail, localEmail)
                .map { it.trim().lowercase() }

        if (candidateEmails.any { it in adminEmails }) {
            authorities += SimpleGrantedAuthority(SecurityRoles.ROLE_ADMIN)
        }

        return authorities
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
