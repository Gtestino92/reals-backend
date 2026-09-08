package com.reals.backend.controller

import com.reals.backend.config.security.currentuser.CurrentUserId
import com.reals.backend.controller.dto.AdminPendingPenaltyAppealResponse
import com.reals.backend.controller.dto.DecidePenaltyAppealRequest
import com.reals.backend.controller.dto.MyPenaltyAppealResponse
import com.reals.backend.service.PenaltyAppealService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/admin/penalty-appeals")
class AdminPenaltyAppealController(
    private val penaltyAppealService: PenaltyAppealService
) {

    @GetMapping("/pending")
    fun listPendingAppeals(): ResponseEntity<List<AdminPendingPenaltyAppealResponse>> =
        ResponseEntity.ok(
            penaltyAppealService.listPendingAppeals()
                .map { AdminPendingPenaltyAppealResponse.from(it) }
        )

    @PostMapping("/{penaltyId}/decision")
    fun decideAppeal(
        @PathVariable penaltyId: UUID,
        @CurrentUserId adminUserId: UUID,
        @Valid
        @RequestBody request: DecidePenaltyAppealRequest
    ): ResponseEntity<MyPenaltyAppealResponse> =
        ResponseEntity.ok(
            MyPenaltyAppealResponse.from(
                penaltyAppealService.decideAppeal(
                    penaltyId = penaltyId,
                    adminUserId = adminUserId,
                    decision = request.decision,
                    notes = request.notes
                )
            )
        )
}
