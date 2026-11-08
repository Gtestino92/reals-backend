package com.reals.backend.service

import com.reals.backend.config.AccountProvisioningLimitProperties
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

class AccountProvisioningLimitExceeded(val retryAfterSeconds: Long) : RuntimeException("Account provisioning temporarily unavailable")

@Service
class AccountProvisioningLimiter(
    private val properties: AccountProvisioningLimitProperties,
    private val origin: ProvisioningNetworkOrigin,
    private val jdbc: JdbcTemplate,
    private val metrics: MeterRegistry
) {
    fun lockUid(uid: String) {
        if (properties.enabled) lock("provision-uid:$uid")
    }

    private fun lock(key: String) {
        check(TransactionSynchronizationManager.isActualTransactionActive())
        jdbc.query("select pg_advisory_xact_lock(?)", { rs -> while (rs.next()) { /* void result */ } }, ProvisioningNetworkOrigin.lockKey(key))
    }

    fun <T> create(remoteAddr: String?, createUser: () -> T): T {
        if (!properties.enabled) {
            val user = createUser()
            allowedAfterCommit()
            return user
        }
        val key = origin.identifier(requireNotNull(remoteAddr) { "Servlet client address is required" })
        // Lock order: UID, network origin, user insert. All locks last until commit/rollback.
        lock("provision-origin:$key")
        val now = databaseNow()
        val events = jdbc.query(
            "select created_at from account_provisioning_events where origin_hmac = ? and created_at > ? order by created_at, id",
            { rs, _ -> rs.getObject(1, OffsetDateTime::class.java).toInstant() },
            key, OffsetDateTime.ofInstant(now.minus(Duration.ofDays(properties.weeklyWindowDays)), java.time.ZoneOffset.UTC)
        )
        val retry = retryAt(events, now)
        if (retry != null) {
            val delay = Duration.between(now, retry)
            throw AccountProvisioningLimitExceeded(delay.seconds + if (delay.nano > 0) 1 else 0)
        }
        val user = createUser()
        jdbc.update("insert into account_provisioning_events(id, origin_hmac, created_at) values (?, ?, ?)",
            UUID.randomUUID(), key, OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC))
        allowedAfterCommit()
        return user
    }

    internal fun retryAt(events: List<Instant>, now: Instant): Instant? {
        val daily = events.filter { it > now.minus(Duration.ofHours(properties.dailyWindowHours)) }
        val weekly = events.filter { it > now.minus(Duration.ofDays(properties.weeklyWindowDays)) }
        fun due(values: List<Instant>, capacity: Int, window: Duration, outcome: String): Instant? {
            if (values.size < capacity) return null
            metrics.counter("reals.account_provisioning", "outcome", outcome).increment()
            return values[values.size - capacity].plus(window)
        }
        return listOfNotNull(
            due(daily, properties.dailyCapacity, Duration.ofHours(properties.dailyWindowHours), "denied_daily"),
            due(weekly, properties.weeklyCapacity, Duration.ofDays(properties.weeklyWindowDays), "denied_weekly")
        ).maxOrNull()
    }

    private fun allowedAfterCommit() {
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() { metrics.counter("reals.account_provisioning", "outcome", "allowed").increment() }
        })
    }

    private fun databaseNow(): Instant = jdbc.queryForObject("select clock_timestamp()") { rs, _ -> rs.getObject(1, OffsetDateTime::class.java).toInstant() }!!

    @Transactional
    fun cleanup(): Int {
        if (!properties.enabled) return 0
        val cutoff = OffsetDateTime.ofInstant(databaseNow().minus(Duration.ofDays(properties.weeklyWindowDays)), java.time.ZoneOffset.UTC)
        // Expired rows never count again. SKIP LOCKED allows concurrent bounded cleaners.
        return jdbc.update("""
            delete from account_provisioning_events where id in (
                select id from account_provisioning_events where created_at <= ?
                order by created_at, id limit ? for update skip locked
            )
        """.trimIndent(), cutoff, properties.cleanupBatchSize)
    }
}
