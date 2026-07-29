package com.reals.backend.config.security.authentication

import com.github.benmanes.caffeine.cache.Ticker
import com.google.firebase.ErrorCode
import com.google.firebase.auth.AuthErrorCode
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseToken
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FirebaseTokenAuthenticationVerifierTest {

    private val firebaseAuth = mock(FirebaseAuth::class.java)
    private val ticker = FakeTicker()
    private val verifier = FirebaseTokenAuthenticationVerifier(
        firebaseAuth = firebaseAuth,
        properties = FirebaseTokenAuthenticationProperties(
            revocationCacheTtl = Duration.ofSeconds(60)
        ),
        ticker = ticker
    )

    @Test
    fun `valid token is locally validated on every request while revocation check is cached`() {
        val decodedToken = decodedToken()
        `when`(firebaseAuth.verifyIdToken("valid-token", false))
            .thenReturn(decodedToken)
        `when`(firebaseAuth.verifyIdToken("valid-token", true))
            .thenReturn(decodedToken)

        assertSame(decodedToken, verifier.verify("valid-token"))
        assertSame(decodedToken, verifier.verify("valid-token"))

        Mockito.verify(firebaseAuth, Mockito.times(2)).verifyIdToken("valid-token", false)
        Mockito.verify(firebaseAuth, Mockito.times(1)).verifyIdToken("valid-token", true)
    }

    @Test
    fun `first use of a token performs full revocation check`() {
        val decodedToken = decodedToken()
        `when`(firebaseAuth.verifyIdToken("first-token", false))
            .thenReturn(decodedToken)
        `when`(firebaseAuth.verifyIdToken("first-token", true))
            .thenReturn(decodedToken)

        verifier.verify("first-token")

        Mockito.verify(firebaseAuth).verifyIdToken("first-token", true)
    }

    @Test
    fun `full revocation check runs again after cache ttl expires`() {
        val decodedToken = decodedToken()
        `when`(firebaseAuth.verifyIdToken("expiring-token", false))
            .thenReturn(decodedToken)
        `when`(firebaseAuth.verifyIdToken("expiring-token", true))
            .thenReturn(decodedToken)

        verifier.verify("expiring-token")
        ticker.advance(Duration.ofSeconds(61))
        verifier.verify("expiring-token")

        Mockito.verify(firebaseAuth, Mockito.times(2)).verifyIdToken("expiring-token", false)
        Mockito.verify(firebaseAuth, Mockito.times(2)).verifyIdToken("expiring-token", true)
    }

    @Test
    fun `different tokens use different revocation cache entries`() {
        val firstDecodedToken = decodedToken()
        val secondDecodedToken = decodedToken()
        `when`(firebaseAuth.verifyIdToken("first-token", false))
            .thenReturn(firstDecodedToken)
        `when`(firebaseAuth.verifyIdToken("first-token", true))
            .thenReturn(firstDecodedToken)
        `when`(firebaseAuth.verifyIdToken("second-token", false))
            .thenReturn(secondDecodedToken)
        `when`(firebaseAuth.verifyIdToken("second-token", true))
            .thenReturn(secondDecodedToken)

        verifier.verify("first-token")
        verifier.verify("second-token")
        verifier.verify("first-token")
        verifier.verify("second-token")

        Mockito.verify(firebaseAuth, Mockito.times(2)).verifyIdToken("first-token", false)
        Mockito.verify(firebaseAuth, Mockito.times(1)).verifyIdToken("first-token", true)
        Mockito.verify(firebaseAuth, Mockito.times(2)).verifyIdToken("second-token", false)
        Mockito.verify(firebaseAuth, Mockito.times(1)).verifyIdToken("second-token", true)
    }

    @Test
    fun `failed revocation verification result is not cached`() {
        val decodedToken = decodedToken()
        `when`(firebaseAuth.verifyIdToken("revoked-token", false))
            .thenReturn(decodedToken)
        `when`(firebaseAuth.verifyIdToken("revoked-token", true))
            .thenThrow(firebaseException(AuthErrorCode.REVOKED_ID_TOKEN))
            .thenReturn(decodedToken)

        assertThrows<FirebaseAuthException> {
            verifier.verify("revoked-token")
        }
        verifier.verify("revoked-token")
        verifier.verify("revoked-token")

        Mockito.verify(firebaseAuth, Mockito.times(3)).verifyIdToken("revoked-token", false)
        Mockito.verify(firebaseAuth, Mockito.times(2)).verifyIdToken("revoked-token", true)
    }

    @Test
    fun `concurrent requests for same uncached token share one full revocation check`() {
        val token = "concurrent-token"
        val decodedToken = decodedToken()
        val start = CountDownLatch(1)
        val localValidated = CountDownLatch(2)
        val fullStarted = CountDownLatch(1)
        val releaseFull = CountDownLatch(1)
        val fullInvocationCount = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(2)

        `when`(firebaseAuth.verifyIdToken(token, false))
            .thenAnswer {
                localValidated.countDown()
                decodedToken
            }
        `when`(firebaseAuth.verifyIdToken(token, true))
            .thenAnswer {
                fullInvocationCount.incrementAndGet()
                fullStarted.countDown()
                assertTrue(localValidated.await(5, TimeUnit.SECONDS))
                assertTrue(releaseFull.await(5, TimeUnit.SECONDS))
                decodedToken
            }

        try {
            val futures = List(2) {
                executor.submit<FirebaseToken> {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    verifier.verify(token)
                }
            }

            start.countDown()
            assertTrue(fullStarted.await(5, TimeUnit.SECONDS))
            assertTrue(localValidated.await(5, TimeUnit.SECONDS))
            assertEquals(1, fullInvocationCount.get())
            releaseFull.countDown()

            futures.forEach { future ->
                assertSame(decodedToken, future.get(5, TimeUnit.SECONDS))
            }

            Mockito.verify(firebaseAuth, Mockito.times(2)).verifyIdToken(token, false)
            Mockito.verify(firebaseAuth, Mockito.times(1)).verifyIdToken(token, true)
        } finally {
            releaseFull.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `failed local verification result is not cached`() {
        val decodedToken = decodedToken()
        `when`(firebaseAuth.verifyIdToken("invalid-token", false))
            .thenThrow(firebaseException(AuthErrorCode.INVALID_ID_TOKEN))
            .thenReturn(decodedToken)
        `when`(firebaseAuth.verifyIdToken("invalid-token", true))
            .thenReturn(decodedToken)

        assertThrows<FirebaseAuthException> {
            verifier.verify("invalid-token")
        }
        verifier.verify("invalid-token")

        Mockito.verify(firebaseAuth, Mockito.times(2)).verifyIdToken("invalid-token", false)
        Mockito.verify(firebaseAuth, Mockito.times(1)).verifyIdToken("invalid-token", true)
    }

    @Test
    fun `expired or invalid token is rejected even with successful revocation cache entry`() {
        val decodedToken = decodedToken()
        `when`(firebaseAuth.verifyIdToken("previously-valid-token", false))
            .thenReturn(decodedToken)
            .thenThrow(firebaseException(AuthErrorCode.EXPIRED_ID_TOKEN))
        `when`(firebaseAuth.verifyIdToken("previously-valid-token", true))
            .thenReturn(decodedToken)

        verifier.verify("previously-valid-token")
        assertThrows<FirebaseAuthException> {
            verifier.verify("previously-valid-token")
        }

        Mockito.verify(firebaseAuth, Mockito.times(2)).verifyIdToken("previously-valid-token", false)
        Mockito.verify(firebaseAuth, Mockito.times(1)).verifyIdToken("previously-valid-token", true)
    }

    private fun decodedToken(): FirebaseToken =
        mock(FirebaseToken::class.java)

    private fun firebaseException(authErrorCode: AuthErrorCode): FirebaseAuthException =
        FirebaseAuthException(
            ErrorCode.UNAUTHENTICATED,
            "token rejected",
            null,
            null,
            authErrorCode
        )

    private class FakeTicker : Ticker {
        private var nanos: Long = 0

        override fun read(): Long = nanos

        fun advance(duration: Duration) {
            nanos += duration.toNanos()
        }
    }
}
