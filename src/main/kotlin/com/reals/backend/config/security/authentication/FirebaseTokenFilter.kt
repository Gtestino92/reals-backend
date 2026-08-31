package com.reals.backend.config.security.authentication

import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.google.firebase.auth.FirebaseAuthException
import com.reals.backend.config.security.SecurityRoles
import com.reals.backend.config.security.currentuser.CurrentUserAuthContext
import com.reals.backend.domain.User
import com.reals.backend.domain.UserStatus
import com.reals.backend.service.AuthOriginPolicy
import com.reals.backend.service.EffectiveAccountBan
import com.reals.backend.service.PenaltyService
import com.reals.backend.service.UserService
import com.reals.backend.service.exception.DomainErrorCode
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
    private val environmentExposurePolicy: EnvironmentExposurePolicy,
    private val userService: UserService,
    private val penaltyService: PenaltyService,
    private val firebaseTokenAuthenticationVerifier: FirebaseTokenAuthenticationVerifier,
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
            (
                request.method.equals("POST", ignoreCase = true) &&
                    path == "/api/auth/password-reset"
            ) ||
            (
                request.method.equals("GET", ignoreCase = true) &&
                    path == "/api/legal/documents/current"
            ) ||
            (
                environmentExposurePolicy.localDevEndpointsAllowed() &&
                    path.startsWith("/api/local-dev/")
            ) ||
            path == "/actuator/health" ||
            path.startsWith("/actuator/health/") ||
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
            val decoded = firebaseTokenAuthenticationVerifier.verify(token)
            val signInProvider = FirebaseSignInProvider.fromToken(decoded)
            if (signInProvider == null) {
                SecurityContextHolder.clearContext()
                writeUnauthorized(
                    response = response,
                    code = "UNSUPPORTED_AUTH_PROVIDER",
                    message = "Firebase sign-in provider is unsupported"
                )
                return
            }

            val user = userService.findByFirebaseUid(decoded.uid)

            if (
                user != null &&
                !AuthOriginPolicy.authenticationAllowed(user.authOrigin, signInProvider)
            ) {
                SecurityContextHolder.clearContext()
                writeUnauthorized(
                    response = response,
                    code = "AUTH_METHOD_NOT_ALLOWED",
                    message = "Authentication method is not allowed for this account"
                )
                return
            }

            if (user?.status == UserStatus.ACTIVE) {
                val effectiveBan = penaltyService.resolveEffectiveBan(userId = user.id)
                if (effectiveBan != null) {
                    SecurityContextHolder.clearContext()
                    writeBanned(
                        response = response,
                        ban = effectiveBan
                    )
                    return
                }
            }

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
                        CurrentUserAuthContext(
                            userId = user.id,
                            firebaseUid = decoded.uid,
                            email = decoded.email,
                            emailVerified = decoded.isEmailVerified,
                            signInProvider = signInProvider
                        ),
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
                            email = decoded.email,
                            emailVerified = decoded.isEmailVerified,
                            signInProvider = signInProvider
                        ),
                        null,
                        listOf(SimpleGrantedAuthority(SecurityRoles.ROLE_FIREBASE_AUTHENTICATED))
                    )
                } else {
                    UsernamePasswordAuthenticationToken(
                        CurrentUserAuthContext(
                            userId = user.id,
                            firebaseUid = decoded.uid,
                            email = decoded.email,
                            emailVerified = decoded.isEmailVerified,
                            signInProvider = signInProvider
                        ),
                        null,
                        authoritiesForActiveUser(
                            user = user,
                            firebaseUid = decoded.uid,
                            firebaseEmail = decoded.email,
                            firebaseEmailVerified = decoded.isEmailVerified
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
        ) || (
            request.method.equals("POST", ignoreCase = true) &&
            path == "/api/me/deletion/finalization"
        )
    }

    internal fun authoritiesForActiveUser(
        user: User,
        firebaseUid: String,
        firebaseEmail: String?,
        firebaseEmailVerified: Boolean
    ): List<SimpleGrantedAuthority> {
        val authorities =
            mutableListOf(SimpleGrantedAuthority(SecurityRoles.ROLE_USER))

        val normalizedFirebaseEmail = firebaseEmail
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }

        if (
            user.status == UserStatus.ACTIVE &&
            user.firebaseUid == firebaseUid &&
            firebaseEmailVerified &&
            normalizedFirebaseEmail != null &&
            normalizedFirebaseEmail in adminEmails
        ) {
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

    private fun writeBanned(
        response: HttpServletResponse,
        ban: EffectiveAccountBan
    ) {
        response.status = HttpServletResponse.SC_FORBIDDEN
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(
            when (ban.expiresAt) {
                null ->
                    """{"code":"${DomainErrorCode.ACCOUNT_PERMANENTLY_BANNED.name}","error":"Forbidden","message":"Account is permanently banned"}"""
                else ->
                    """{"code":"${DomainErrorCode.ACCOUNT_TEMPORARILY_BANNED.name}","error":"Forbidden","message":"Account is temporarily banned","expiresAt":"${ban.expiresAt}"}"""
            }
        )
    }
}
