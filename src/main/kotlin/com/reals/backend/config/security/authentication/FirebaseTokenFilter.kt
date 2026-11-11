package com.reals.backend.config.security.authentication

import com.reals.backend.config.security.preventAdministrativeCaching
import com.reals.backend.adminauth.*
import com.reals.backend.config.security.appcheck.FirebaseAppCheckFilter
import com.reals.backend.config.environment.EnvironmentExposurePolicy
import com.google.firebase.auth.FirebaseAuthException
import com.reals.backend.config.security.SecurityRoles
import com.reals.backend.config.security.currentuser.CurrentUserAuthContext
import com.reals.backend.domain.PenaltyType
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
    private val adminAccessService: AdminAccessService? = null
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)
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
        request.preventAdministrativeCaching(response)
        val path = request.servletPath.ifBlank { request.requestURI.removePrefix(request.contextPath) }
        val administrativeRequest = path == "/api/admin-auth" || path.startsWith("/api/admin-auth/") ||
            path == "/api/admin" || path.startsWith("/api/admin/") || path.startsWith("/actuator/") ||
            path.startsWith("/api/local-dev/") || request.getHeader("X-Reals-Admin-Grant") != null
        if (administrativeRequest) response.setHeader("Cache-Control", "no-store")
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
            val adminAuth = path == "/api/admin-auth" || path.startsWith("/api/admin-auth/")
            val privileged = path == "/api/admin" || path.startsWith("/api/admin/") ||
                path == "/actuator/info" || path == "/actuator/metrics" || path.startsWith("/actuator/metrics/") ||
                path.startsWith("/api/local-dev/")
            val administrative = adminAuth || privileged || request.getHeader("X-Reals-Admin-Grant") != null
            if (adminAuth || privileged) response.setHeader("Cache-Control", "no-store")
            val decoded = if (administrative) firebaseTokenAuthenticationVerifier.verifyAdministrative(token)
                else firebaseTokenAuthenticationVerifier.verify(token)
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
                    if (
                        effectiveBan.type == PenaltyType.PERMANENT_BAN &&
                        isPermanentBanAppealAllowedPath(request)
                    ) {
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

            var adminGranted = false
            if (administrative) {
                val appId = request.getAttribute(FirebaseAppCheckFilter.VALID_APP_ID_ATTRIBUTE) as? String
                val authTime = (decoded.claims["auth_time"] as? Number)?.toLong()
                val identity = if (user != null && appId != null && authTime != null)
                    AdminRequestIdentity(user.id, decoded.uid, decoded.email, decoded.isEmailVerified, signInProvider, authTime, appId)
                    else null
                val eligible = identity != null && adminAccessService?.eligible(identity) == true
                if (eligible) {
                    request.setAttribute("reals.admin.identity", identity)
                    adminGranted = adminAccessService.grantError(identity, request.getHeader("X-Reals-Admin-Grant")) == null
                }
                if ((adminAuth && !eligible) || (privileged && !adminGranted)) {
                    val code = if (!eligible) "ADMIN_ACCESS_DENIED" else
                        adminAccessService.grantError(identity, request.getHeader("X-Reals-Admin-Grant")) ?: "ADMIN_MFA_REQUIRED"
                    response.status = 403
                    response.contentType = MediaType.APPLICATION_JSON_VALUE
                    response.writer.write("""{"code":"$code","error":"Forbidden","message":"Administrative access is denied"}""")
                    return
                }
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
                        ) + if (adminGranted) listOf(SimpleGrantedAuthority(SecurityRoles.ROLE_ADMIN)) else emptyList()
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
        } catch (ex: RuntimeException) {
            if (!administrativeRequest) throw ex
            SecurityContextHolder.clearContext()
            response.status = HttpServletResponse.SC_SERVICE_UNAVAILABLE
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.writer.write("""{"code":"ADMIN_AUTHENTICATION_UNAVAILABLE","error":"Service Unavailable","message":"Administrative authentication is temporarily unavailable"}""")
            return
        }

        filterChain.doFilter(request, response)
    }

    private fun isPermanentBanAppealAllowedPath(request: HttpServletRequest): Boolean {
        val path = request.servletPath.ifBlank {
            request.requestURI.removePrefix(request.contextPath)
        }

        return path == "/api/me/ban/appeal" &&
            (
                request.method.equals("GET", ignoreCase = true) ||
                    request.method.equals("POST", ignoreCase = true)
            )
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
        // Eligibility alone never creates administrative authority.
        return listOf(SimpleGrantedAuthority(SecurityRoles.ROLE_USER))
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
