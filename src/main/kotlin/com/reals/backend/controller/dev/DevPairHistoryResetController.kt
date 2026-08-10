package com.reals.backend.controller.dev

import com.reals.backend.service.localdev.LocalDevPairHistoryResetService
import jakarta.validation.Valid
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@Profile("local-nodb", "local-postgres", "local-firebase", "dev")
@RequestMapping("/api/local-dev/pair-history")
class DevPairHistoryResetController(
    private val pairHistoryResetService: LocalDevPairHistoryResetService
) {

    @PostMapping("/reset")
    fun resetPairHistory(
        @Valid @RequestBody request: DevPairHistoryResetRequest
    ): ResponseEntity<DevPairHistoryResetResponse> {
        val result = pairHistoryResetService.resetPairHistory(
            userIdA = request.userIdA,
            userIdB = request.userIdB
        )

        return ResponseEntity.ok(
            DevPairHistoryResetResponse(
                matchesDeleted = result.matchesDeleted,
                connectionsDeleted = result.connectionsDeleted,
                chatsDeleted = result.chatsDeleted
            )
        )
    }
}

data class DevPairHistoryResetRequest(
    val userIdA: UUID,
    val userIdB: UUID
)

data class DevPairHistoryResetResponse(
    val matchesDeleted: Int,
    val connectionsDeleted: Int,
    val chatsDeleted: Int
)
