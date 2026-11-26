package com.reals.backend.repository

import com.reals.backend.repository.matching.MatchmakingSqlFragments.effectiveBanExclusion
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.nio.ByteBuffer
import java.time.OffsetDateTime
import java.util.UUID

data class ContactParticipation(val consentVersion: Int, val acceptedAt: OffsetDateTime, val keyVersion: Int?, val syncedAt: OffsetDateTime?)
data class ContactOverlap(val count: Int, val generationA: UUID, val generationB: UUID)

@Repository
class SharedContactRepository(private val jdbc: JdbcTemplate, private val named: NamedParameterJdbcTemplate) {
    fun revision(owner: UUID): UUID = jdbc.queryForObject(
        "select contact_mutation_revision from users where id=?", UUID::class.java, owner
    )!!

    fun advanceRevision(owner: UUID) {
        jdbc.update("update users set contact_mutation_revision=? where id=?", UUID.randomUUID(), owner)
    }

    fun ownerStatus(owner: UUID) = jdbc.query("""
        select u.contact_mutation_revision, p.consent_version, p.accepted_at, p.key_version, p.synced_at
        from users u left join contact_participation p on p.user_id=u.id
        where u.id=?
    """, { rs, _ ->
        rs.getObject("contact_mutation_revision", UUID::class.java) to
            rs.getObject("consent_version")?.let { rs.toParticipation() }
    }, owner).single()

    fun participation(owner: UUID) = jdbc.query("select * from contact_participation where user_id=?", { rs, _ ->
        rs.toParticipation()
    }, owner).singleOrNull()

    private fun java.sql.ResultSet.toParticipation() = ContactParticipation(
        getInt("consent_version"), getObject("accepted_at", OffsetDateTime::class.java),
        getObject("key_version") as Int?, getObject("synced_at", OffsetDateTime::class.java)
    )

    fun accept(owner: UUID, version: Int, now: OffsetDateTime) {
        withdraw(owner)
        jdbc.update("insert into contact_participation(user_id,consent_version,accepted_at,generation) values(?,?,?,?)",
            owner, version, now, UUID.randomUUID())
    }

    fun withdraw(owner: UUID) {
        jdbc.update("delete from contact_participation where user_id=?", owner)
    }

    fun deleteAccountData(owner: UUID) {
        advanceRevision(owner)
        withdraw(owner)
        jdbc.update("delete from contact_replacements where user_id=?", owner)
    }

    fun clear(owner: UUID) {
        jdbc.update("delete from contact_identifiers where user_id=?", owner)
        jdbc.update("update contact_participation set key_version=null, synced_at=null, generation=? where user_id=? and key_version is not null",
            UUID.randomUUID(), owner)
    }

    fun identifiers(owner: UUID) = jdbc.query("select digest from contact_identifiers where user_id=?", { rs, _ ->
        ByteBuffer.wrap(rs.getBytes(1))
    }, owner).toSet()

    fun recordReplacement(owner: UUID, now: OffsetDateTime, limit: Int): Boolean {
        jdbc.update("delete from contact_replacements where user_id=? and replaced_at<=?", owner, now.minusDays(1))
        val used = jdbc.queryForObject("select count(*) from contact_replacements where user_id=?", Int::class.java, owner)!!
        if (used >= limit) return false
        jdbc.update("insert into contact_replacements(id,user_id,replaced_at) values(?,?,?)", UUID.randomUUID(), owner, now)
        return true
    }

    fun synchronize(owner: UUID, newIdentifiers: Set<ByteBuffer>, version: Int, now: OffsetDateTime, invalidate: Boolean) {
        if (invalidate) clear(owner)
        // Direct parameter binding keeps pseudonyms outside Spring's bind-value diagnostics.
        jdbc.execute(ConnectionCallback { connection ->
            connection.prepareStatement("insert into contact_identifiers(user_id,digest) values(?,?)").use { stmt ->
                for (digest in newIdentifiers) {
                    stmt.setObject(1, owner)
                    stmt.setBytes(2, digest.array())
                    stmt.addBatch()
                }
                try {
                    stmt.executeBatch()
                } catch (_: java.sql.SQLException) {
                    // Driver batch exceptions can include rendered parameters.
                    throw org.springframework.dao.DataAccessResourceFailureException("Could not persist contact selection")
                }
            }
        })
        jdbc.update("update contact_participation set key_version=?,synced_at=? where user_id=?", version, now, owner)
    }

    // Keep the planner inside the eligible window even when a popular digest occurs across many owners.
    internal val overlapSql = """
            with anchor_set as materialized (
                select a.generation, x.digest from contact_participation a
                join contact_identifiers x on x.user_id=a.user_id
                join users ua on ua.id=a.user_id and ua.status='ACTIVE'
                where a.user_id=:anchor and a.key_version=:version and a.consent_version=:consent
                ${effectiveBanExclusion("a.user_id")}
            )
            select b.user_id, a.generation ga, b.generation gb, count(*) n
            from contact_participation b
            join users ub on ub.id=b.user_id and ub.status='ACTIVE'
            cross join lateral (
                select digest from contact_identifiers where user_id=b.user_id offset 0
            ) y
            join anchor_set a on a.digest=y.digest
            where b.user_id in (:candidates) and b.key_version=:version and b.consent_version=:consent
              ${effectiveBanExclusion("b.user_id")}
            group by b.user_id,a.generation,b.generation
        """

    fun overlaps(anchor: UUID, candidates: List<UUID>, version: Int, consent: Int): Map<UUID, ContactOverlap> {
        if (candidates.isEmpty()) return emptyMap()
        return named.query(overlapSql, mapOf("anchor" to anchor, "candidates" to candidates, "now" to OffsetDateTime.now(), "version" to version, "consent" to consent)) { rs, _ ->
            rs.getObject("user_id", UUID::class.java) to ContactOverlap(rs.getInt("n"),rs.getObject("ga", UUID::class.java),rs.getObject("gb", UUID::class.java))
        }.toMap()
    }

    fun snapshot(matchId: UUID, range: String, overlap: ContactOverlap) {
        jdbc.update("insert into match_contact_context(match_id,contact_range,generation_a,generation_b) values(?,?,?,?)",
            matchId, range, overlap.generationA, overlap.generationB)
    }

    fun visibleRange(matchId: UUID, viewer: UUID, version: Int, consent: Int, now: OffsetDateTime): String? =
        named.query("""
            select s.contact_range from match_contact_context s
            join matches m on m.id=s.match_id
            join contact_participation a on a.user_id=m.user_a_id and a.generation=s.generation_a
            join contact_participation b on b.user_id=m.user_b_id and b.generation=s.generation_b
            join users ua on ua.id=a.user_id and ua.status='ACTIVE'
            join users ub on ub.id=b.user_id and ub.status='ACTIVE'
            join chats c on c.match_id=m.id and c.chat_type='FIRST_CHAT' and c.status='ACTIVE' and c.timeout_at>:now
            where m.id=:id and m.state='CHAT_ACTIVE' and :viewer in (a.user_id,b.user_id)
              and a.key_version=:version and b.key_version=:version and a.consent_version=:consent and b.consent_version=:consent
              and not exists (select 1 from user_blocks u where (u.blocker_user_id=a.user_id and u.blocked_user_id=b.user_id)
                or (u.blocker_user_id=b.user_id and u.blocked_user_id=a.user_id))
              ${effectiveBanExclusion("a.user_id")}
              ${effectiveBanExclusion("b.user_id")}
        """, mapOf("id" to matchId, "viewer" to viewer, "version" to version, "consent" to consent, "now" to now)) { rs, _ -> rs.getString(1) }.singleOrNull()
}
