package com.reals.backend.config.filter

/*
TODO(firebase):
Firebase Admin is already declared in pom.xml. Activate this filter together
with FirebaseConfig and register it in SecurityConfig for dev/prod.

Flow when active:
1. Extract Bearer token from Authorization header.
2. Verify via FirebaseAuth.getInstance().verifyIdToken(token)
   -> FirebaseToken.
3. Call UserService.findOrCreate(decoded.uid) -> get internal UUID.
4. Set principal = uuid.toString() in SecurityContext
   -> @CurrentUserId reads it.
*/

/*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.reals.backend.service.UserService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
@Profile("dev", "prod")
class FirebaseTokenFilter(
    private val userService: UserService
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {

        val authHeader = request.getHeader("Authorization")

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            response.sendError(
                HttpServletResponse.SC_UNAUTHORIZED,
                "Missing Authorization header"
            )
            return
        }

        try {

            val decoded = FirebaseAuth.getInstance()
                .verifyIdToken(authHeader.removePrefix("Bearer ").trim())

            val user = userService.findOrCreate(decoded.uid)

            SecurityContextHolder.getContext().authentication =
                UsernamePasswordAuthenticationToken(
                    user.id.toString(),
                    null,
                    listOf(SimpleGrantedAuthority("ROLE_USER"))
                )

        } catch (e: FirebaseAuthException) {

            log.warn("Firebase token invalid: ${e.message}")

            response.sendError(
                HttpServletResponse.SC_UNAUTHORIZED,
                "Invalid or expired token"
            )

            return
        }

        filterChain.doFilter(request, response)
    }
}
*/
