package com.reals.backend.integration.service

import com.reals.backend.domain.AuditAggregateType
import com.reals.backend.domain.AuditEventType
import com.reals.backend.integration.BaseIT
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class AuditEventServiceIntegrationTest : BaseIT() {

    @Test
    fun `records audit event with sanitized metadata`() {
        val actorUserId = UUID.randomUUID()
        val targetUserId = UUID.randomUUID()
        val aggregateId = UUID.randomUUID()

        val event = auditEventService.record(
            eventType = AuditEventType.SAFETY_REPORT_CREATED,
            aggregateType = AuditAggregateType.SAFETY_REPORT,
            aggregateId = aggregateId,
            actorUserId = actorUserId,
            targetUserId = targetUserId,
            metadata = mapOf(
                "contextType" to "CHAT",
                "status" to "PENDING",
                "omitted" to null
            )
        )

        val saved = auditEventRepository.findById(event.id).orElseThrow()

        assertEquals(AuditEventType.SAFETY_REPORT_CREATED, saved.eventType)
        assertEquals(AuditAggregateType.SAFETY_REPORT, saved.aggregateType)
        assertEquals(aggregateId, saved.aggregateId)
        assertEquals(actorUserId, saved.actorUserId)
        assertEquals(targetUserId, saved.targetUserId)
        assertNotNull(saved.createdAt)
        assertTrue(saved.metadataJson!!.contains("contextType"))
        assertTrue(saved.metadataJson!!.contains("PENDING"))
        assertFalse(saved.metadataJson!!.contains("omitted"))
    }

    @Test
    fun `records audit event without metadata`() {
        val event = auditEventService.record(
            eventType = AuditEventType.PROFILE_ACTIVATED,
            aggregateType = AuditAggregateType.PROFILE,
            aggregateId = UUID.randomUUID()
        )

        val saved = auditEventRepository.findById(event.id).orElseThrow()

        assertEquals(AuditEventType.PROFILE_ACTIVATED, saved.eventType)
        assertEquals(null, saved.metadataJson)
    }
}
