package com.reals.backend.adminauth

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.*
import java.time.Instant
import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator
import java.time.Duration
import javax.crypto.spec.SecretKeySpec

class AdminTotpTest {
    private val key = "12345678901234567890".toByteArray()
    @Test fun `RFC 6238 SHA1 vectors and six digit truncation`() {
        val times = listOf(59L, 1111111109, 1111111111, 1234567890, 2000000000, 20000000000)
        val codes = listOf("94287082", "07081804", "14050471", "89005924", "69279037", "65353130")
        val generator = TimeBasedOneTimePasswordGenerator(Duration.ofSeconds(30), 8, "HmacSHA1")
        times.zip(codes).forEach { (time, code) ->
            assertEquals(code, generator.generateOneTimePasswordString(SecretKeySpec(key, "HmacSHA1"), Instant.ofEpochSecond(time)))
            assertNotNull(AdminTotp(key).acceptedStep(code.takeLast(6), Instant.ofEpochSecond(time), -1))
        }
    }
    @Test fun `drift leading zero replay and malformed codes`() {
        val totp = AdminTotp(key)
        val now = Instant.ofEpochSecond(1111111109)
        val step = now.epochSecond / 30
        assertEquals(step, totp.acceptedStep("081804", now, -1))
        assertEquals(step, totp.acceptedStep("081804", now.minusSeconds(30), -1))
        assertEquals(step, totp.acceptedStep("081804", now.plusSeconds(30), -1))
        assertNull(totp.acceptedStep("081804", now.plusSeconds(60), -1))
        assertNull(totp.acceptedStep("081804", now, step))
        listOf("81804", " 081804", "abcdef", "0000000").forEach { assertNull(totp.acceptedStep(it, now, -1)) }
    }
    @Test fun `drift expiry exact boundary before equality and after`() {
        val totp = AdminTotp(key)
        assertNotNull(totp.acceptedStep("287082", Instant.ofEpochSecond(90).minusNanos(1), -1))
        assertNull(totp.acceptedStep("287082", Instant.ofEpochSecond(90), -1))
        assertNull(totp.acceptedStep("287082", Instant.ofEpochSecond(90).plusNanos(1), -1))
        assertNull(totp.acceptedStep("000000", Instant.ofEpochSecond(59), -1))
    }
    @Test fun `missing factor disables and invalid configuration fails startup`() {
        assertNull(AdminMfaProperties().factorId)
        assertThrows<IllegalArgumentException> { AdminMfaProperties("short", "v1") }
        assertThrows<IllegalArgumentException> { AdminMfaProperties("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "") }
        assertThrows<IllegalArgumentException> { AdminMfaProperties(grantTtlSeconds = 901) }
        assertThrows<IllegalArgumentException> { AdminIdentityProperties(allowedWebAppIds = "1:123:android:abc") }
    }
}
