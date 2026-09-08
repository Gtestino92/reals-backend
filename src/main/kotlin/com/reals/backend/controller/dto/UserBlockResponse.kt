package com.reals.backend.controller.dto

import com.reals.backend.domain.UserBlock
import com.reals.backend.domain.UserBlockSource
import java.time.OffsetDateTime
import java.util.UUID

data class UserBlockResponse(
    val id: UUID,
    val source: UserBlockSource,
    val createdAt: OffsetDateTime
) {
    companion object {
        fun from(block: UserBlock) = UserBlockResponse(
            id = block.id,
            source = block.source,
            createdAt = block.createdAt
        )
    }
}
