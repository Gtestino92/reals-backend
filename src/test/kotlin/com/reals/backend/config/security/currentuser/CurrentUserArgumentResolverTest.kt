package com.reals.backend.config.security.currentuser

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.core.MethodParameter
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.context.request.NativeWebRequest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CurrentUserArgumentResolverTest {

    private val currentUserIdArgumentResolver = CurrentUserIdArgumentResolver()
    private val currentUserAuthArgumentResolver = CurrentUserAuthArgumentResolver()

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `current user id resolver supports auth context principal`() {
        val userId = UUID.randomUUID()
        setPrincipal(
            CurrentUserAuthContext(
                userId = userId,
                firebaseUid = "firebase-user",
                email = "user@example.com",
                emailVerified = false
            )
        )

        val resolved = currentUserIdArgumentResolver.resolveArgument(
            currentUserIdParameter(),
            null,
            mock(NativeWebRequest::class.java),
            null
        )

        assertEquals(userId, resolved)
    }

    @Test
    fun `current user auth resolver returns auth context principal`() {
        val context = CurrentUserAuthContext(
            userId = UUID.randomUUID(),
            firebaseUid = "firebase-user",
            email = "user@example.com",
            emailVerified = false
        )
        setPrincipal(context)

        val resolved = currentUserAuthArgumentResolver.resolveArgument(
            currentUserAuthParameter(),
            null,
            mock(NativeWebRequest::class.java),
            null
        )

        assertEquals(context, resolved)
    }

    @Test
    fun `current user auth resolver supports legacy string principal`() {
        val userId = UUID.randomUUID()
        setPrincipal(userId.toString())

        val resolved = currentUserAuthArgumentResolver.resolveArgument(
            currentUserAuthParameter(),
            null,
            mock(NativeWebRequest::class.java),
            null
        )

        assertEquals(userId, resolved.userId)
        assertNull(resolved.firebaseUid)
        assertNull(resolved.email)
        assertEquals(true, resolved.emailVerified)
    }

    private fun setPrincipal(principal: Any) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null)
    }

    private fun currentUserIdParameter(): MethodParameter =
        MethodParameter(
            TestController::class.java.getDeclaredMethod(
                "currentUserId",
                UUID::class.java
            ),
            0
        )

    private fun currentUserAuthParameter(): MethodParameter =
        MethodParameter(
            TestController::class.java.getDeclaredMethod(
                "currentUserAuth",
                CurrentUserAuthContext::class.java
            ),
            0
        )

    private class TestController {
        @Suppress("UNUSED_PARAMETER")
        fun currentUserId(
            @CurrentUserId userId: UUID
        ) {
        }

        @Suppress("UNUSED_PARAMETER")
        fun currentUserAuth(
            @CurrentUserAuth authContext: CurrentUserAuthContext
        ) {
        }
    }
}
