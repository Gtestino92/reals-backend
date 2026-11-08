package com.reals.backend.integration.postgres

import com.reals.backend.config.security.authentication.FirebasePrincipal
import com.reals.backend.config.security.SecurityRoles
import com.reals.backend.service.AccountProvisioningLimiter
import com.reals.backend.service.AccountProvisioningLimitExceeded
import com.reals.backend.service.ProvisioningNetworkOrigin
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@AutoConfigureMockMvc
@TestPropertySource(properties = [
    "security.account-provisioning-limit.enabled=true",
    "security.account-provisioning-limit.hmac-secret=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
    "security.account-provisioning-limit.cleanup-batch-size=2"
])
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AccountProvisioningPostgresIT : PostgresITBase() {
    @Autowired lateinit var limiter: AccountProvisioningLimiter
    @Autowired lateinit var origin: ProvisioningNetworkOrigin
    @Autowired lateinit var manager: PlatformTransactionManager
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var metrics: MeterRegistry
    private val ip = "192.0.2.1"

    @BeforeEach
    fun clearEvents() { jdbcTemplate.update("delete from account_provisioning_events") }

    private fun provision(uid: String = UUID.randomUUID().toString(), address: String = ip) =
        userService.provisionFromFirebase(uid, remoteAddr = address)
    private fun count() = jdbcTemplate.queryForObject("select count(*) from account_provisioning_events", Long::class.java)!!
    private fun seed(days: Long, amount: Int, address: String = ip) {
        repeat(amount) {
            jdbcTemplate.update("insert into account_provisioning_events values (?, ?, ?)", UUID.randomUUID(), origin.identifier(address), OffsetDateTime.now().minusDays(days))
        }
    }

    @Test
    fun `three creates fourth denied independent IP and existing UID bypass`() {
        val first = provision("first")
        repeat(2) { provision() }
        val ex = assertThrows<AccountProvisioningLimitExceeded> { provision() }
        assertTrue(ex.retryAfterSeconds in 86390..86400)
        assertEquals(first.id, provision("first").id)
        provision(address = "192.0.2.2")
        assertEquals(4, count())
        assertEquals(4, userRepository.count())
        assertTrue(jdbcTemplate.queryForList("select origin_hmac from account_provisioning_events", String::class.java).all { it!!.matches(Regex("[a-f0-9]{64}")) })
    }

    @Test
    fun `weekly cap applies with daily capacity free`() {
        seed(2, 3)
        provision(); provision()
        assertThrows<AccountProvisioningLimitExceeded> { provision() }
        assertEquals(5, count())
    }

    @Test
    fun `legacy linking conflicts and reactivation never consume quota`() {
        repeat(3) { provision() }
        val legacy = userService.createUser("legacy@example.com")
        val linked = userService.provisionFromFirebase("legacy", legacy.email, emailVerified = true, remoteAddr = ip)
        assertEquals(legacy.id, linked.id)
        assertThrows<com.reals.backend.service.exception.DomainConflictException> {
            userService.provisionFromFirebase("different", legacy.email, remoteAddr = ip)
        }
        userService.deleteUser(linked.id)
        userService.reactivateUser(linked.id)
        assertEquals(3, count())
    }

    @Test
    fun `account insert failure and later rollback leave no event or allowed metric`() {
        val before = metrics.find("reals.account_provisioning").tag("outcome", "allowed").counter()?.count() ?: 0.0
        assertThrows<org.springframework.dao.DataIntegrityViolationException> {
            userService.provisionFromFirebase("x".repeat(300), remoteAddr = ip)
        }
        assertEquals(0, count())
        assertThrows<IllegalStateException> {
            TransactionTemplate(manager).executeWithoutResult {
                limiter.create(ip) { userRepository.saveAndFlush(com.reals.backend.domain.User(email = "rollback@example.com")) }
                error("rollback after event insert")
            }
        }
        assertEquals(0, count())
        assertEquals(0, userRepository.count())
        assertEquals(before, metrics.find("reals.account_provisioning").tag("outcome", "allowed").counter()?.count() ?: 0.0)
    }

    private fun concurrent(uids: List<String>, addresses: List<String>): List<Boolean> {
        val pool = Executors.newFixedThreadPool(uids.size)
        val ready = CountDownLatch(uids.size)
        val start = CountDownLatch(1)
        try {
            val futures = uids.mapIndexed { i, uid -> pool.submit(Callable {
                ready.countDown(); check(start.await(10, TimeUnit.SECONDS))
                try { provision(uid, addresses[i]); true } catch (_: AccountProvisioningLimitExceeded) { false }
            }) }
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `concurrent distinct UIDs cannot exceed daily quota`() {
        val result = concurrent((1..8).map { "uid-$it" }, List(8) { ip })
        assertEquals(3, result.count { it }); assertEquals(3, count()); assertEquals(3, userRepository.count())
    }

    @Test
    fun `concurrent distinct UIDs cannot exceed weekly quota`() {
        seed(2, 4)
        val result = concurrent((1..8).map { "uid-$it" }, List(8) { ip })
        assertEquals(1, result.count { it }); assertEquals(5, count())
    }

    @Test
    fun `concurrent same UID across origins charges only once even at quota`() {
        seed(0, 2)
        assertTrue(concurrent(List(8) { "same" }, (1..8).map { "192.0.2.$it" }).all { it })
        assertEquals(3, count()); assertEquals(1, userRepository.count())
    }

    @Test
    fun `HTTP status retry header and spoofed headers use servlet address`() {
        fun request(uid: String) = post("/api/me/provision")
            .with(authentication(UsernamePasswordAuthenticationToken(FirebasePrincipal(uid, null, false), null,
                listOf(SimpleGrantedAuthority(SecurityRoles.ROLE_FIREBASE_AUTHENTICATED)))))
            .with { it.remoteAddr = ip; it }
        repeat(3) { mvc.perform(request("http-$it")).andExpect(status().isCreated).andExpect(jsonPath("$.id").exists()) }
        mvc.perform(request("http-3").header("X-Forwarded-For", "198.51.100.1").header("Forwarded", "for=198.51.100.2").header("X-Real-IP", "198.51.100.3"))
            .andExpect(status().isTooManyRequests)
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.code").value("ACCOUNT_PROVISIONING_LIMIT_EXCEEDED"))
            .andDo { assertTrue(it.response.getHeader("Retry-After")!!.toLong() in 86390..86400) }
        mvc.perform(request("http-0")).andExpect(status().isCreated)
        assertEquals(3, count())
    }

    @Test
    fun `bounded cleanup keeps all weekly records and is replay safe`() {
        seed(8, 5); seed(6, 2)
        assertEquals(2, limiter.cleanup()); assertEquals(5, count())
        assertEquals(2, limiter.cleanup()); assertEquals(1, limiter.cleanup()); assertEquals(0, limiter.cleanup())
        assertEquals(2, count())
        // Explicit JDBC timestamptz materialization through actual PostgreSQL driver.
        val instant = jdbcTemplate.queryForObject("select created_at from account_provisioning_events limit 1") { rs, _ -> rs.getObject(1, OffsetDateTime::class.java).toInstant() }
        assertNotNull(instant)
    }
}
