package com.reals.backend.service.notification

import com.reals.backend.domain.PushNotificationType
import java.nio.charset.StandardCharsets
import java.util.UUID

fun schedulingProposalsReceivedAggregateId(
    connectionId: UUID,
    roundNumber: Int
): UUID =
    UUID.nameUUIDFromBytes(
        "${PushNotificationType.SCHEDULING_PROPOSALS_RECEIVED.name}:$connectionId:$roundNumber"
            .toByteArray(StandardCharsets.UTF_8)
    )

fun schedulingAvailableAggregateId(
    userId: UUID,
    connectionIds: Collection<UUID>
): UUID =
    UUID.nameUUIDFromBytes(
        buildString {
            append(PushNotificationType.SCHEDULING_AVAILABLE.name)
            append(':')
            append(userId)
            connectionIds
                .map { it.toString() }
                .distinct()
                .sorted()
                .forEach { connectionId ->
                    append(':')
                    append(connectionId)
                }
        }.toByteArray(StandardCharsets.UTF_8)
    )
