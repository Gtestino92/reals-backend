package com.reals.backend.service

import com.reals.backend.domain.AuditAggregateType
import com.reals.backend.domain.AuditEvent
import com.reals.backend.domain.AuditEventType
import com.reals.backend.repository.AuditEventRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Service
@Transactional
class AuditEventService(
    private val auditEventRepository: AuditEventRepository,
    private val objectMapper: ObjectMapper
) {

    fun record(
        eventType: AuditEventType,
        aggregateType: AuditAggregateType,
        aggregateId: UUID,
        actorUserId: UUID? = null,
        targetUserId: UUID? = null,
        metadata: Map<String, Any?> = emptyMap()
    ): AuditEvent {
        val sanitizedMetadata = metadata.filterValues { it != null }
        val metadataJson = if (sanitizedMetadata.isEmpty()) {
            null
        } else {
            runCatching {
                objectMapper.writeValueAsString(sanitizedMetadata)
            }.getOrNull()
        }

        return auditEventRepository.save(
            AuditEvent(
                eventType = eventType,
                aggregateType = aggregateType,
                aggregateId = aggregateId,
                actorUserId = actorUserId,
                targetUserId = targetUserId,
                metadataJson = metadataJson
            )
        )
    }
}
