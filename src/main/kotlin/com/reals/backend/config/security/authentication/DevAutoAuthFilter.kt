package com.reals.backend.config.security.authentication

import com.reals.backend.config.security.SecurityRoles
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Profile
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
@Profile("local-nodb", "local-postgres")
class DevAutoAuthFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {

        val userId = request.getHeader(OVERRIDE_HEADER) ?: DEV_USER_ID

        val auth = UsernamePasswordAuthenticationToken(
            userId,
            null,
            listOf(SimpleGrantedAuthority(SecurityRoles.ROLE_USER))
        )

        SecurityContextHolder.getContext().authentication = auth

        filterChain.doFilter(request, response)
    }

    companion object {

        const val DEV_USER_ID =
            "00000000-0000-0000-0000-000000000001"

        /**
         * Send this header to impersonate a different user in local tests.
         */
        const val OVERRIDE_HEADER = "X-Dev-User-Id"
    }
}
