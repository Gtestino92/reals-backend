package com.reals.backend.controller

import com.reals.backend.config.security.currentuser.CurrentUserId
import com.reals.backend.controller.dto.MyPenaltyAppealResponse
import com.reals.backend.controller.dto.SubmitPenaltyAppealRequest
import com.reals.backend.service.PenaltyAppealService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class MePenaltyAppealController(
    private val penaltyAppealService: PenaltyAppealService
) {

    @GetMapping("/api/me/ban/appeal")
    fun getMyBanAppeal(
        @CurrentUserId userId: UUID
    ): ResponseEntity<MyPenaltyAppealResponse> =
        ResponseEntity.ok(
            MyPenaltyAppealResponse.from(
                penaltyAppealService.getMyAppeal(userId = userId)
            )
        )

    @PostMapping("/api/me/ban/appeal")
    fun submitMyBanAppeal(
        @CurrentUserId userId: UUID,
        @Valid
        @RequestBody request: SubmitPenaltyAppealRequest
    ): ResponseEntity<MyPenaltyAppealResponse> =
        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(
                MyPenaltyAppealResponse.from(
                    penaltyAppealService.submitMyAppeal(
                        userId = userId,
                        statement = request.statement
                    )
                )
            )
}
