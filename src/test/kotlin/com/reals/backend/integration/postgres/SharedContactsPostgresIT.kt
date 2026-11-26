package com.reals.backend.integration.postgres

import com.reals.backend.domain.Gender
import com.reals.backend.repository.SharedContactRepository
import com.reals.backend.service.contacts.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import com.reals.backend.service.LegalDocumentService
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.time.OffsetDateTime
import java.util.*
import java.util.concurrent.*
import kotlin.test.*

@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@TestPropertySource(properties=["shared-contacts.enabled=true","shared-contacts.import-enabled=true","shared-contacts.disclosure-enabled=true"])
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SharedContactsPostgresIT : PostgresITBase() {
    @Autowired lateinit var participation: ContactParticipationService
    @Autowired lateinit var contacts: SharedContactRepository
    @Autowired lateinit var context: MatchContactService

    @Autowired lateinit var legal: LegalDocumentService
    @Autowired lateinit var mvc: org.springframework.test.web.servlet.MockMvc
    @Autowired lateinit var transactions: org.springframework.transaction.support.TransactionTemplate

    private fun authenticated(id: UUID) =
        authentication(UsernamePasswordAuthenticationToken(id.toString(), null, listOf(SimpleGrantedAuthority("ROLE_USER"))))

    private fun selection(n: Int) = ContactSelection((0 until n).map { "+1202555%04d".format(Locale.ROOT, 100+it) })
    private fun owner() = userService.createUser("test-${UUID.randomUUID()}@example.invalid").id

    @Test fun `committed replacements preserve or permanently suppress the original range`() {
        val a = createActiveProfile("a@example.invalid","Aster",Gender.FEMALE,setOf(Gender.MALE))
        val b = createActiveProfile("b@example.invalid","Birch",Gender.MALE,setOf(Gender.FEMALE))
        for (id in listOf(a,b)) {
            assertFailsWith<ContactRequestError> { participation.replace(id, selection(1)) }
            participation.accept(id,1)
            participation.replace(id,selection(5))
        }
        val match = matchService.createMatch(a,b)
        chatService.startFirstChat(match.id)
        fun range() = context.range(match.id,a,null)
        assertEquals(SharedContactRange.ONE_TO_FIVE,range())
        val url = "/api/matches/${match.id}/chat"
        mvc.perform(get(url).with(authenticated(a)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.sharedContactRange").value("ONE_TO_FIVE"))
            .andExpect(jsonPath("$.sharedContactCount").doesNotExist())
            .andExpect(jsonPath("$.generation").doesNotExist())
        mvc.perform(get(url).with(authenticated(owner()))).andExpect(status().isForbidden)
        assertNull(context.range(match.id,owner(),null))
        for (id in listOf(a,b)) participation.replace(id,selection(11))
        assertEquals(SharedContactRange.ONE_TO_FIVE,range())
        val before = participation.status(a)
        assertEquals(before, participation.replace(a,selection(11)).status)
        assertFailsWith<ContactRequestError> { participation.replace(a,ContactSelection(listOf("invalid"))) }
        assertEquals(11, contacts.identifiers(a).size)
        participation.replace(a,selection(10))
        assertNull(range())
        participation.replace(a,selection(11))
        assertNull(range())
        participation.remove(b,true)
        participation.accept(b,1)
        participation.replace(b,selection(11))
        assertNull(range())
    }

    @Test fun `blocked restricted and retired snapshots do not disclose`() {
        val a = owner()
        val b = owner()
        for (id in listOf(a,b)) {
            participation.accept(id,1)
            participation.replace(id,selection(1))
        }
        val match = matchService.createMatch(a,b)
        // Query the stored context via a synthetic active First Chat.
        chatService.startFirstChat(match.id)
        fun range() = context.range(match.id,a,null)
        assertEquals(SharedContactRange.ONE_TO_FIVE,range())
        transactions.executeWithoutResult {
            jdbcTemplate.update("insert into user_blocks(blocker_user_id,blocked_user_id,source) values(?,?,'MANUAL')",b,a)
            assertNull(range())
            it.setRollbackOnly()
        }
        transactions.executeWithoutResult {
            jdbcTemplate.update("insert into penalties(user_id,reason,type) values(?,'synthetic','PERMANENT_BAN')",b)
            assertNull(range())
            assertTrue(contacts.overlaps(a,listOf(b),1,1).isEmpty())
            it.setRollbackOnly()
        }
        jdbcTemplate.update("update contact_participation set key_version=2 where user_id=?",b)
        assertNull(range())
        assertTrue(contacts.overlaps(a,listOf(b),1,1).isEmpty())
        participation.replace(b,selection(1))
        assertNull(range())
    }

    @Test fun `old match cannot gain context on first import`() {
        val fixture = createMatchWithFirstChat()
        for (id in listOf(fixture.userAId,fixture.userBId)) {
            participation.accept(id,1)
            participation.replace(id,selection(1))
        }
        assertNull(context.range(fixture.matchId,fixture.userAId,null))
    }

    @Test fun `rotation obsolete consent and deletion cannot restore evidence`() {
        val id = owner()
        participation.accept(id,1)
        participation.replace(id,selection(2))
        jdbcTemplate.update("update contact_participation set key_version=2 where user_id=?",id)
        assertEquals(ContactSyncState.RESYNC_REQUIRED,participation.status(id).state)
        participation.replace(id,selection(2))
        jdbcTemplate.update("update contact_participation set consent_version=2 where user_id=?",id)
        assertFailsWith<ContactRequestError> { participation.replace(id,selection(3)) }
        participation.accept(id,1)
        assertTrue(contacts.identifiers(id).isEmpty())
        participation.replace(id,selection(2))
        userService.deleteUser(id)
        assertNull(contacts.participation(id))
        userService.reactivateUser(id)
        assertTrue(contacts.identifiers(id).isEmpty())
        assertNull(participation.status(id).acceptedAt)
    }

    @Test fun `competing replacements share a committed rolling quota`() {
        val id = owner()
        participation.accept(id,1)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(6)
        try {
            val attempts = (1..6).map { count -> pool.submit(Callable {
                start.await()
                try { participation.replace(id,selection(count)); 200 }
                catch (error: ContactRequestError) { error.status }
            }) }
            start.countDown()
            assertEquals(listOf(200,200,200,200,200,429),attempts.map { it.get(20,TimeUnit.SECONDS) }.sorted())
        } finally { pool.shutdownNow() }
        participation.remove(id,false)
        participation.remove(id,true)
        participation.accept(id,1)
        assertFailsWith<ContactRequestError> { participation.replace(id,selection(0)) }
        assertTrue(contacts.identifiers(id).isEmpty())
    }

    @Test fun `owner HTTP contract rejects whole invalid selection without echo`() {
        val id = owner()
        val base = "/api/me/shared-contacts"
        fun send(path: String, json: String) = mvc.perform(put("$base/$path").with(authenticated(id))
            .contentType("application/json").content(json))
        legal.currentDocuments().forEach { legal.recordAction(id,it.type,it.version,it.requiredAction) }
        send("selection","""{"numbers":[]}""").andExpect(status().isConflict)
        send("consent","""{"version":1}""").andExpect(status().isOk)
        send("selection","""{"numbers":["202 555-0100","+12025550100"],"defaultRegion":"US"}""")
            .andExpect(status().isOk).andExpect(jsonPath("$.status.state").value("SYNCED"))
            .andExpect(jsonPath("$.submittedCount").value(2)).andExpect(jsonPath("$.distinctCount").value(1))
        assertEquals(1,contacts.identifiers(id).size)
        val bad = send("selection","""{"numbers":["synthetic-invalid"]}""").andExpect(status().isBadRequest).andReturn()
        assertFalse(bad.response.contentAsString.contains("synthetic-invalid"))
        send("selection","""{"numbers":["2025550100"],"defaultRegion":"USA"}""").andExpect(status().isBadRequest)
        send("selection"," ".repeat(300001)).andExpect(status().`is`(413))
        assertEquals(1,contacts.identifiers(id).size)
        send("selection","""{"numbers":[]}""").andExpect(status().isOk)
        assertTrue(contacts.identifiers(id).isEmpty())
        mvc.perform(delete("$base/consent").with(authenticated(id))).andExpect(status().isNoContent)
        mvc.perform(get(base).with(authenticated(id))).andExpect(jsonPath("$.state").value("NOT_CONFIGURED"))
    }

    @Test fun `withdrawal holding the owner lock wins against match capture`() {
        val a = owner()
        val b = owner()
        for (id in listOf(a,b)) {
            participation.accept(id,1)
            participation.replace(id,selection(1))
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val started = CountDownLatch(1)
            val match = transactions.execute {
                participation.remove(a,true)
                executor.submit(Callable {
                    started.countDown()
                    matchService.createMatch(a,b)
                }).also { assertTrue(started.await(10,TimeUnit.SECONDS)) }
            }!!.get(20,TimeUnit.SECONDS)
            assertEquals(0,jdbcTemplate.queryForObject("select count(*) from match_contact_context where match_id=?",Int::class.java,match.id))
        } finally { executor.shutdownNow() }
    }

    @Test fun `in-flight import cannot recreate a withdrawn or deleted selection`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            for (deleteAccount in listOf(false,true)) {
                val id = owner()
                participation.accept(id,1)
                participation.replace(id,selection(1))
                val started = CountDownLatch(1)
                val pending = transactions.execute {
                    userService.lockActiveUserOrThrow(id,"test privacy transition")
                    val import = executor.submit(Callable {
                        started.countDown()
                        runCatching { participation.replace(id,selection(2)) }.exceptionOrNull()
                    })
                    assertTrue(started.await(10,TimeUnit.SECONDS))
                    if (deleteAccount) userService.deleteUser(id) else participation.remove(id,true)
                    import
                }!!
                val failure = pending.get(20,TimeUnit.SECONDS)
                if (deleteAccount) assertIs<com.reals.backend.service.exception.DomainConflictException>(failure)
                else assertIs<ContactRequestError>(failure)
                assertNull(contacts.participation(id))
                assertTrue(contacts.identifiers(id).isEmpty())
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun `rollback restores the selection generation and quota`() {
        val id = owner()
        participation.accept(id,1)
        participation.replace(id,selection(3))
        val before = contacts.identifiers(id)
        transactions.executeWithoutResult {
            participation.replace(id,selection(0))
            it.setRollbackOnly()
        }
        assertEquals(before,contacts.identifiers(id))
        assertEquals(1,jdbcTemplate.queryForObject("select count(*) from contact_replacements where user_id=?",Int::class.java,id))
    }

    @Test fun `quota eligibility includes exact boundary`() {
        val id = owner()
        val now = OffsetDateTime.parse("2026-01-01T00:00:00Z")
        assertTrue(contacts.recordReplacement(id,now,1))
        assertFalse(contacts.recordReplacement(id,now.plusDays(1).minusNanos(1000),1))
        assertTrue(contacts.recordReplacement(id,now.plusDays(1),1))
        assertTrue(contacts.recordReplacement(id,now.plusDays(2).plusNanos(1000),1))
    }

    @Test fun `bounded PostgreSQL intersection uses the owner index`() {
        // Synthetic opaque bytes, never an address book. 501 owners, 50 candidates, ten shared values.
        jdbcTemplate.execute("insert into users(id) select md5(i::text)::uuid from generate_series(1,501) i")
        jdbcTemplate.execute("insert into contact_participation select id,1,now(),1,now(),gen_random_uuid() from users")
        jdbcTemplate.execute("""
            insert into contact_identifiers
            select id,sha256(convert_to(case when i<=10 then i::text else id::text||i::text end,'UTF8'))
            from users cross join generate_series(1,200) i
        """)
        jdbcTemplate.execute("vacuum analyze contact_identifiers")
        jdbcTemplate.execute("analyze contact_participation")
        val ids = jdbcTemplate.queryForList("select id from users order by id limit 51",UUID::class.java).requireNoNulls()
        val candidates = ids.drop(1)
        assertEquals(List(50) { 10 }, contacts.overlaps(ids.first(),candidates,1,1).values.map { it.count })
        val plan = org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbcTemplate).queryForList(
            "explain (analyze, buffers) " + contacts.overlapSql,
            mapOf("anchor" to ids.first(), "candidates" to candidates, "version" to 1, "consent" to 1, "now" to OffsetDateTime.now()),
            String::class.java
        ).requireNoNulls()
        assertTrue(plan.any { it.contains("contact_identifiers_pkey") })
        assertFalse(plan.any { it.contains("Seq Scan on contact_identifiers") })
    }

    companion object {
        @JvmStatic @DynamicPropertySource fun key(registry: DynamicPropertyRegistry) {
            registry.add("shared-contacts.secret") { Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }) }
        }
    }
}
