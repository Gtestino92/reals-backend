package com.reals.backend.service

import com.reals.backend.config.AccountProvisioningLimitProperties
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.time.Duration
import java.util.Base64

class AccountProvisioningLimiterTest {
    private val properties = AccountProvisioningLimitProperties(enabled = true,
        hmacSecret = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }))
    private val origin = ProvisioningNetworkOrigin(properties)
    private val metrics = SimpleMeterRegistry()
    private val limiter = AccountProvisioningLimiter(properties, origin, Mockito.mock(JdbcTemplate::class.java), metrics)
    private val now = Instant.parse("2026-11-08T12:00:00Z")

    @Test
    fun `daily boundary before equality and after`() {
        val oldest = now.minus(Duration.ofHours(24))
        val events = listOf(oldest, now.minusSeconds(60), now.minusSeconds(30))
        assertEquals(now, limiter.retryAt(events, now.minusNanos(1)))
        assertNull(limiter.retryAt(events, now))
        assertNull(limiter.retryAt(events, now.plusNanos(1)))
    }

    @Test
    fun `weekly boundary before equality and after`() {
        val events = (0L..4L).map { now.minus(Duration.ofDays(7)).plusSeconds(it * 3600) }
        assertEquals(now, limiter.retryAt(events, now.minusNanos(1)))
        assertNull(limiter.retryAt(events, now))
        assertNull(limiter.retryAt(events, now.plusNanos(1)))
    }

    @Test
    fun `retry waits for both quotas including reduced capacities`() {
        val events = listOf(now.minus(Duration.ofDays(6)), now.minus(Duration.ofDays(5)), now.minusSeconds(300), now.minusSeconds(200), now.minusSeconds(100))
        assertEquals(events[0].plus(Duration.ofDays(7)), limiter.retryAt(events, now))
        assertEquals(1.0, metrics.get("reals.account_provisioning").tag("outcome", "denied_daily").counter().count())
        assertEquals(1.0, metrics.get("reals.account_provisioning").tag("outcome", "denied_weekly").counter().count())
    }

    @Test
    fun `numeric origin canonicalization and private stable HMAC`() {
        val key = origin.identifier("192.0.2.1")
        assertEquals(key, origin.identifier("192.000.002.001"))
        assertEquals(key, origin.identifier("::ffff:192.0.2.1"))
        assertEquals(64, key.length)
        assertFalse(key.contains("192.0.2.1"))
        assertNotEquals(key, origin.identifier("192.0.2.2"))
        assertEquals(origin.identifier("2001:db8:abcd:1::1"), origin.identifier("2001:0db8:abcd:0001:ffff::abcd"))
        assertNotEquals(origin.identifier("2001:db8:abcd:1::1"), origin.identifier("2001:db8:abcd:2::1"))
        assertThrows<IllegalArgumentException> { origin.identifier("localhost") }
        assertThrows<IllegalArgumentException> { origin.identifier("192.0.2.256") }
        assertThrows<IllegalArgumentException> { origin.identifier("2001:db8::1%eth0") }
    }

    @Test
    fun `lowered capacity waits for enough events to expire`() {
        val events = (1L..8L).map { now.minusSeconds(900 - it * 100) }
        assertEquals(events[3].plus(Duration.ofDays(7)), limiter.retryAt(events, now))
    }

    @Test
    fun `configuration binding fails startup only when enabled without valid key`() {
        val runner = org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withUserConfiguration(com.reals.backend.config.AccountProvisioningLimitConfig::class.java)
        runner.run { assertNull(it.startupFailure) }
        runner.withPropertyValues("security.account-provisioning-limit.enabled=true")
            .run { assertNotNull(it.startupFailure) }
        runner.withPropertyValues("security.account-provisioning-limit.enabled=true", "security.account-provisioning-limit.hmac-secret=bad!")
            .run { assertNotNull(it.startupFailure) }
        runner.withPropertyValues("security.account-provisioning-limit.enabled=true", "security.account-provisioning-limit.hmac-secret=" + properties.hmacSecret)
            .run { assertNull(it.startupFailure) }
    }

    @Test
    fun `configuration validates enabled secret and combinations`() {
        assertFalse(AccountProvisioningLimitProperties().enabled)
        assertThrows<IllegalArgumentException> { AccountProvisioningLimitProperties(enabled = true) }
        assertThrows<IllegalArgumentException> { AccountProvisioningLimitProperties(enabled = true, hmacSecret = "bad!") }
        assertThrows<IllegalArgumentException> { AccountProvisioningLimitProperties(enabled = true, hmacSecret = "YWJj") }
        assertThrows<IllegalArgumentException> { AccountProvisioningLimitProperties(dailyCapacity = 0) }
        assertThrows<IllegalArgumentException> { AccountProvisioningLimitProperties(weeklyCapacity = 2) }
        assertThrows<IllegalArgumentException> { AccountProvisioningLimitProperties(dailyWindowHours = 48, weeklyWindowDays = 1) }
        assertThrows<IllegalArgumentException> { AccountProvisioningLimitProperties(cleanupBatchSize = 0) }
    }
}
