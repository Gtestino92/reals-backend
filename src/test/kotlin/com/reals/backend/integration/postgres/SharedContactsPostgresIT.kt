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
@TestPropertySource(properties=["shared-contacts.enabled=true","shared-contacts.max-distinct=12","shared-contacts.import-enabled=true","shared-contacts.disclosure-enabled=true"])
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

    private fun acceptCurrent(id: UUID, version: Int) =
        participation.accept(id, version, participation.status(id).mutationRevision)

    private fun replaceCurrent(id: UUID, selection: ContactSelection) =
        participation.replace(id, selection, participation.status(id).mutationRevision)

    private fun addCurrent(id: UUID, selection: ContactSelection) =
        participation.add(id, selection, participation.status(id).mutationRevision)

    private fun selection(n: Int) = ContactSelection((0 until n).map { "+1202555%04d".format(Locale.ROOT, 100+it) })
    private fun owner() = userService.createUser("test-${UUID.randomUUID()}@example.invalid").id

    @Test fun `committed replacements preserve or permanently suppress the original range`() {
        val a = createActiveProfile("a@example.invalid","Aster",Gender.FEMALE,setOf(Gender.MALE))
        val b = createActiveProfile("b@example.invalid","Birch",Gender.MALE,setOf(Gender.FEMALE))
        for (id in listOf(a,b)) {
            assertFailsWith<ContactRequestError> { replaceCurrent(id, selection(1)) }
            acceptCurrent(id,1)
            replaceCurrent(id,selection(5))
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
            .andExpect(jsonPath("$.mutationRevision").doesNotExist())
        mvc.perform(get(url).with(authenticated(owner()))).andExpect(status().isForbidden)
        assertNull(context.range(match.id,owner(),null))
        for (id in listOf(a,b)) addCurrent(id,selection(6))
        assertEquals(SharedContactRange.ONE_TO_FIVE,range())
        for (id in listOf(a,b)) replaceCurrent(id,selection(11))
        assertEquals(11, contacts.overlaps(a, listOf(b), 1, 1).getValue(b).count)
        assertEquals(SharedContactRange.ONE_TO_FIVE,range())
        val before = participation.status(a)
        assertEquals(before, replaceCurrent(a,selection(11)).status)
        assertFailsWith<ContactRequestError> { replaceCurrent(a,ContactSelection(listOf("invalid"))) }
        assertEquals(11, contacts.identifiers(a).size)
        replaceCurrent(a,selection(10))
        assertNull(range())
        assertEquals("CONTACT_MUTATION_STALE", assertFailsWith<ContactRequestError> {
            participation.replace(a,selection(11),before.mutationRevision)
        }.code)
        assertNull(range())
        replaceCurrent(a,selection(11))
        assertNull(range())
        participation.remove(b,true)
        acceptCurrent(b,1)
        replaceCurrent(b,selection(11))
        assertNull(range())
    }

    @Test fun `new matches capture overlap from additions`() {
        val a = owner()
        val b = owner()
        acceptCurrent(a, 1)
        acceptCurrent(b, 1)
        replaceCurrent(a, selection(0))
        replaceCurrent(b, selection(6))
        addCurrent(a, selection(6))
        val match = matchService.createMatch(a, b)
        chatService.startFirstChat(match.id)
        assertEquals(SharedContactRange.SIX_TO_TEN, context.range(match.id, a, null))
    }

    @Test fun `blocked restricted and retired snapshots do not disclose`() {
        val a = owner()
        val b = owner()
        for (id in listOf(a,b)) {
            acceptCurrent(id,1)
            replaceCurrent(id,selection(1))
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
        replaceCurrent(b,selection(1))
        assertNull(range())
    }

    @Test fun `old match cannot gain context on first import`() {
        val fixture = createMatchWithFirstChat()
        for (id in listOf(fixture.userAId,fixture.userBId)) {
            acceptCurrent(id,1)
            replaceCurrent(id,selection(1))
        }
        assertNull(context.range(fixture.matchId,fixture.userAId,null))
    }

    @Test fun `rotation obsolete consent and deletion cannot restore evidence`() {
        val id = owner()
        acceptCurrent(id,1)
        replaceCurrent(id,selection(2))
        jdbcTemplate.update("update contact_participation set key_version=2 where user_id=?",id)
        assertEquals(ContactSyncState.RESYNC_REQUIRED,participation.status(id).state)
        val retired = contacts.identifiers(id)
        assertEquals("CONTACT_FULL_REPLACEMENT_REQUIRED", assertFailsWith<ContactRequestError> { addCurrent(id,selection(3)) }.code)
        assertEquals(retired, contacts.identifiers(id))
        replaceCurrent(id,selection(2))
        jdbcTemplate.update("update contact_participation set consent_version=2 where user_id=?",id)
        assertEquals("CONTACT_CONSENT_REQUIRED", assertFailsWith<ContactRequestError> { addCurrent(id,selection(3)) }.code)
        assertFailsWith<ContactRequestError> { replaceCurrent(id,selection(3)) }
        acceptCurrent(id,1)
        assertTrue(contacts.identifiers(id).isEmpty())
        replaceCurrent(id,selection(2))
        userService.deleteUser(id)
        assertNull(contacts.participation(id))
        userService.reactivateUser(id)
        assertTrue(contacts.identifiers(id).isEmpty())
        assertNull(participation.status(id).acceptedAt)
    }

    @Test fun `competing replacements share a committed rolling quota`() {
        val id = owner()
        acceptCurrent(id,1)
        val revision = participation.status(id).mutationRevision
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(6)
        try {
            val attempts = (1..6).map { count -> pool.submit(Callable {
                start.await()
                try { participation.replace(id,selection(count),revision); 200 }
                catch (error: ContactRequestError) { error.status }
            }) }
            start.countDown()
            assertEquals(listOf(200,409,409,409,409,409),attempts.map { it.get(20,TimeUnit.SECONDS) }.sorted())
        } finally { pool.shutdownNow() }
        for (count in 7..10) replaceCurrent(id,selection(count))
        participation.remove(id,false)
        participation.remove(id,true)
        acceptCurrent(id,1)
        assertFailsWith<ContactRequestError> { replaceCurrent(id,selection(0)) }
        assertTrue(contacts.identifiers(id).isEmpty())
    }

    @Test fun `owner HTTP contract rejects whole invalid selection without echo`() {
        val id = owner()
        val base = "/api/me/shared-contacts"
        fun send(path: String, json: String, revision: UUID = participation.status(id).mutationRevision) = mvc.perform(put("$base/$path").with(authenticated(id))
            .header("X-Contact-Revision",revision.toString()).contentType("application/json").content(json))
        legal.currentDocuments().forEach { legal.recordAction(id,it.type,it.version,it.requiredAction) }
        mvc.perform(get(base).with(authenticated(id)))
            .andExpect(jsonPath("$.mutationRevision").value(participation.status(id).mutationRevision.toString()))
        mvc.perform(put("$base/consent").with(authenticated(id)).contentType("application/json").content("""{"version":1}"""))
            .andExpect(status().`is`(428)).andExpect(jsonPath("$.code").value("CONTACT_REVISION_REQUIRED"))
        send("selection","""{"numbers":[]}""").andExpect(status().isConflict)
        send("consent","""{"version":1}""").andExpect(status().isOk)
        send("selection","""{"numbers":["202 555-0100","+12025550100"],"defaultRegion":"US"}""")
            .andExpect(status().isOk).andExpect(jsonPath("$.status.state").value("SYNCED"))
            .andExpect(jsonPath("$.submittedCount").value(2)).andExpect(jsonPath("$.distinctCount").value(1))
        assertEquals(1,contacts.identifiers(id).size)
        mvc.perform(put("$base/selection/additions").with(authenticated(id)).contentType("application/json").content("""{"numbers":[]}"""))
            .andExpect(status().`is`(428)).andExpect(jsonPath("$.code").value("CONTACT_REVISION_REQUIRED"))
        send("selection/additions","""{"numbers":["202 555-0100","202 555-0101","+12025550101"],"defaultRegion":"US"}""")
            .andExpect(status().isOk).andExpect(jsonPath("$.status.state").value("SYNCED"))
            .andExpect(jsonPath("$.submittedCount").value(3)).andExpect(jsonPath("$.addedDistinctCount").value(1))
            .andExpect(jsonPath("$.totalDistinctCount").value(2))
        val stale = participation.status(id).mutationRevision
        participation.remove(id,true)
        acceptCurrent(id,1)
        val renewed = participation.status(id)
        send("selection/additions","""{"numbers":[]}""",stale)
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CONTACT_MUTATION_STALE"))
        send("selection/additions","""{"numbers":[]}""")
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CONTACT_FULL_REPLACEMENT_REQUIRED"))
        send("selection","""{"numbers":["+12025550100"]}""",stale)
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CONTACT_MUTATION_STALE"))
        assertEquals(renewed,participation.status(id))
        assertTrue(contacts.identifiers(id).isEmpty())
        send("selection","""{"numbers":["+12025550100"]}""").andExpect(status().isOk)
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
            acceptCurrent(id,1)
            replaceCurrent(id,selection(1))
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
                acceptCurrent(id,1)
                replaceCurrent(id,selection(1))
                val started = CountDownLatch(1)
                val pending = transactions.execute {
                    userService.lockActiveUserOrThrow(id,"test privacy transition")
                    val import = executor.submit(Callable {
                        started.countDown()
                        runCatching { replaceCurrent(id,selection(2)) }.exceptionOrNull()
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

    @Test fun `delayed additions cannot undo clear withdrawal or renewed consent`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            for (boundary in listOf("clear", "withdraw", "renew")) {
                val id = owner()
                acceptCurrent(id, 1)
                val revision = replaceCurrent(id, selection(1)).status.mutationRevision
                val started = CountDownLatch(1)
                val pending = transactions.execute {
                    participation.remove(id, withdraw = boundary != "clear")
                    val attempt = executor.submit(Callable {
                        participation.requireImportConsent(id, revision)
                        started.countDown()
                        assertFailsWith<ContactRequestError> { participation.add(id, selection(2), revision) }.code
                    })
                    assertTrue(started.await(10, TimeUnit.SECONDS))
                    if (boundary == "renew") acceptCurrent(id, 1)
                    attempt
                }!!
                assertEquals("CONTACT_MUTATION_STALE", pending.get(20, TimeUnit.SECONDS))
                assertTrue(contacts.identifiers(id).isEmpty())
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun `privacy boundaries invalidate outstanding owner intents`() {
        val id = owner()
        for (boundary in listOf("empty clear", "clear", "withdraw", "recovery")) {
            acceptCurrent(id,1)
            val revision = participation.status(id).mutationRevision
            participation.requireImportConsent(id,revision)
            when (boundary) {
                "empty clear", "clear" -> participation.remove(id,false)
                "withdraw" -> participation.remove(id,true)
                else -> {
                    userService.deleteUser(id)
                    userService.reactivateUser(id)
                }
            }
            assertEquals("CONTACT_MUTATION_STALE",assertFailsWith<ContactRequestError> {
                participation.accept(id,1,revision)
            }.code)
            acceptCurrent(id,1)
            val fresh = participation.status(id)
            fun quota() = jdbcTemplate.queryForObject("select count(*) from contact_replacements where user_id=?",Int::class.java,id)
            val used = quota()
            assertEquals("CONTACT_MUTATION_STALE",assertFailsWith<ContactRequestError> {
                participation.replace(id,selection(1),revision)
            }.code)
            assertEquals("CONTACT_MUTATION_STALE",assertFailsWith<ContactRequestError> {
                participation.add(id,selection(2),revision)
            }.code)
            assertEquals(used,quota())
            assertEquals(fresh,participation.status(id))
            assertTrue(contacts.identifiers(id).isEmpty())
            replaceCurrent(id,selection(1))
        }
    }

    @Test fun `rollback restores the selection generation and quota`() {
        val id = owner()
        acceptCurrent(id,1)
        replaceCurrent(id,selection(3))
        val before = contacts.identifiers(id)
        transactions.executeWithoutResult {
            replaceCurrent(id,selection(0))
            it.setRollbackOnly()
        }
        assertEquals(before,contacts.identifiers(id))
        assertEquals(1,jdbcTemplate.queryForObject("select count(*) from contact_replacements where user_id=?",Int::class.java,id))
    }

    @Test fun `atomic additions enforce combined capacity and share the effective mutation quota`() {
        val id = owner()
        acceptCurrent(id, 1)
        replaceCurrent(id, selection(1))
        val original = contacts.identifiers(id)
        val initial = participation.status(id)
        fun tail(from: Int, to: Int) = ContactSelection(selection(to).numbers.drop(from))
        assertFailsWith<IllegalStateException> {
            transactions.executeWithoutResult {
                addCurrent(id, tail(1, 5))
                error("Roll back the import")
            }
        }
        assertEquals(initial, participation.status(id))
        assertEquals(original, contacts.identifiers(id))
        val added = addCurrent(id, tail(1, 5))
        assertEquals(4, added.addedDistinctCount)
        assertEquals(5, added.totalDistinctCount)
        assertTrue(contacts.identifiers(id).containsAll(original))
        assertNotEquals(initial.mutationRevision, added.status.mutationRevision)
        assertNotEquals(initial.syncedAt, added.status.syncedAt)
        val atLimit = addCurrent(id, tail(4, 12))
        assertEquals(7, atLimit.addedDistinctCount)
        assertEquals(12, atLimit.totalDistinctCount)
        val full = contacts.identifiers(id)
        assertEquals("CONTACT_SET_LIMIT", assertFailsWith<ContactRequestError> { addCurrent(id, tail(11, 13)) }.code)
        assertEquals(atLimit.status, participation.status(id))
        assertEquals(full, contacts.identifiers(id))
        replaceCurrent(id, selection(1))
        addCurrent(id, tail(1, 2))
        val before = participation.status(id)
        val identifiers = contacts.identifiers(id)
        val replay = addCurrent(id, selection(2))
        assertEquals(0, replay.addedDistinctCount)
        assertEquals(before, replay.status)
        assertEquals("CONTACT_REPLACEMENT_LIMIT", assertFailsWith<ContactRequestError> { addCurrent(id, selection(3)) }.code)
        assertEquals("CONTACT_REPLACEMENT_LIMIT", assertFailsWith<ContactRequestError> { replaceCurrent(id, selection(0)) }.code)
        assertEquals(before, participation.status(id))
        assertEquals(identifiers, contacts.identifiers(id))
    }

    @Test fun `racing additions and replacements serialize on the captured revision`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            for (raceReplacement in listOf(false, true)) {
                val id = owner()
                acceptCurrent(id, 1)
                val revision = replaceCurrent(id, selection(1)).status.mutationRevision
                val start = CyclicBarrier(2)
                val attempts = (1..2).map { index -> pool.submit(Callable {
                    val input = ContactSelection(listOf(selection(3).numbers[index]))
                    start.await(10, TimeUnit.SECONDS)
                    try {
                        if (raceReplacement && index == 2) participation.replace(id, input, revision)
                        else participation.add(id, input, revision)
                        "OK"
                    } catch (e: ContactRequestError) { e.code }
                }) }
                val results = attempts.map { it.get(20, TimeUnit.SECONDS) }
                assertEquals(listOf("CONTACT_MUTATION_STALE", "OK"), results.sorted())
                val replaced = raceReplacement && results.last() == "OK"
                assertEquals(if (replaced) 1 else 2, contacts.identifiers(id).size)
            }
        } finally { pool.shutdownNow() }
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
