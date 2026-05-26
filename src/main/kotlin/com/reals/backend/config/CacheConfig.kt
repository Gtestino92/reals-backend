package com.reals.backend.config

import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.caffeine.CaffeineCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.TimeUnit

@Configuration
@EnableCaching
class CacheConfig {

    @Bean
    fun cacheManager(): CacheManager {

        val manager = CaffeineCacheManager()

        manager.registerCustomCache(
            CacheNames.PROFILES,
            Caffeine.newBuilder()
                .maximumSize(5_000)
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .build()
        )

        manager.registerCustomCache(
            CacheNames.ACTIVE_LOCKS,
            Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .build()
        )

        manager.registerCustomCache(
            CacheNames.ACTIVE_PENALTY,
            Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .build()
        )

        return manager
    }
}

/**
 * Cache names — use these constants with @Cacheable/@CacheEvict
 * throughout the codebase.
 */
object CacheNames {

    const val PROFILES = "profiles" // Profile data by userId
    const val ACTIVE_LOCKS = "activeLocks" // ActiveInteractionLock by userId
    const val ACTIVE_PENALTY = "activePenalties" // Whether a user has an active penalty
}
