package com.reals.backend.config.security.ratelimit

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Expiry
import com.github.benmanes.caffeine.cache.Ticker

/** Bounded per filter; idle eviction is safe only after a completely empty bucket could refill. */
internal fun tokenBucketCache(ticker: Ticker = Ticker.systemTicker()) = Caffeine.newBuilder()
    .maximumSize(100_000)
    .ticker(ticker)
    .expireAfter(object : Expiry<String, TokenBucket> {
        override fun expireAfterCreate(key: String, value: TokenBucket, currentTime: Long) = value.idleRetentionNanos
        override fun expireAfterUpdate(key: String, value: TokenBucket, currentTime: Long, currentDuration: Long) =
            value.idleRetentionNanos
        override fun expireAfterRead(key: String, value: TokenBucket, currentTime: Long, currentDuration: Long) =
            value.idleRetentionNanos
    })
    .build<String, TokenBucket>()
