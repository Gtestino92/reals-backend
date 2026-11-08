package com.reals.backend.config.security.ratelimit

import com.github.benmanes.caffeine.cache.Ticker
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class TokenBucketTest {
    @Test
    fun `daily bucket survives inactivity and refills at exact boundary`() {
        var nanos = 0L
        val cache = tokenBucketCache(Ticker { nanos })
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val bucket = TokenBucket(5, 5, Duration.ofDays(1), start)
        cache.put("reports", bucket)
        repeat(5) { assertTrue(bucket.tryConsume(start) is RateLimitDecision.Allowed) }
        nanos = Duration.ofMinutes(16).toNanos()
        assertSame(bucket, cache.getIfPresent("reports"))
        assertTrue(bucket.tryConsume(start.plusNanos(nanos)) is RateLimitDecision.Rejected)
        val boundary = start.plus(Duration.ofDays(1))
        assertTrue(bucket.tryConsume(boundary.minusNanos(1)) is RateLimitDecision.Rejected)
        assertEquals(RateLimitDecision.Allowed(4), bucket.tryConsume(boundary))
        assertEquals(RateLimitDecision.Allowed(3), bucket.tryConsume(boundary.plusNanos(1)))
    }

    @Test
    fun `partial refill retains bucket for all periods required to fill capacity`() {
        var nanos = 0L
        val cache = tokenBucketCache(Ticker { nanos })
        val start = Instant.EPOCH
        val bucket = TokenBucket(5, 2, Duration.ofHours(1), start)
        cache.put("partial", bucket)
        repeat(5) { bucket.tryConsume(start) }
        nanos = Duration.ofHours(1).toNanos()
        assertSame(bucket, cache.getIfPresent("partial"))
        assertEquals(RateLimitDecision.Allowed(1), bucket.tryConsume(start.plusSeconds(3600)))
        // Read access renews the full conservative retention period.
        nanos += Duration.ofHours(3).minusNanos(1).toNanos()
        assertSame(bucket, cache.getIfPresent("partial"))
        nanos += Duration.ofHours(3).toNanos()
        assertNull(cache.getIfPresent("partial"))
    }

    @Test
    fun `short buckets still expire after fifteen idle minutes`() {
        var nanos = 0L
        val cache = tokenBucketCache(Ticker { nanos })
        cache.put("short", TokenBucket(10, 10, Duration.ofMinutes(1), Instant.EPOCH))
        nanos = Duration.ofMinutes(15).minusNanos(1).toNanos()
        assertNotNull(cache.getIfPresent("short"))
        nanos += Duration.ofMinutes(15).toNanos()
        assertNull(cache.getIfPresent("short"))
    }

    @Test
    fun `large refill arithmetic saturates instead of overflowing`() {
        val bucket = TokenBucket(Int.MAX_VALUE, Int.MAX_VALUE, Duration.ofSeconds(1), Instant.EPOCH)
        bucket.tryConsume(Instant.EPOCH)
        assertEquals(RateLimitDecision.Allowed(Int.MAX_VALUE - 1), bucket.tryConsume(Instant.EPOCH.plusSeconds(3)))
    }

    @Test
    fun `reset configuration validates positive values even when disabled`() {
        assertThrows(IllegalArgumentException::class.java) { RateLimitProperties(passwordResetCapacity = 0) }
        assertThrows(IllegalArgumentException::class.java) { RateLimitProperties(passwordResetRefillTokens = 0) }
        assertThrows(IllegalArgumentException::class.java) { RateLimitProperties(passwordResetRefillPeriodSeconds = 0) }
    }
}
