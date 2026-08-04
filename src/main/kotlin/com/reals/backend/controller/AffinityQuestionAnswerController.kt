package com.reals.backend.controller

import com.reals.backend.config.security.currentuser.CurrentUserId
import com.reals.backend.controller.dto.AffinityAnswersResponse
import com.reals.backend.controller.dto.PatchAffinityAnswersRequest
import com.reals.backend.service.LegalComplianceService
import com.reals.backend.service.affinity.AffinityAnswerPatch
import com.reals.backend.service.affinity.AffinityQuestionAnswerService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/me/profile/affinity-answers")
@Validated
class AffinityQuestionAnswerController(
    private val answerService: AffinityQuestionAnswerService,
    private val legalComplianceService: LegalComplianceService
) {
    @GetMapping
    fun getMyAffinityAnswers(
        @CurrentUserId userId: UUID
    ): ResponseEntity<AffinityAnswersResponse> =
        ResponseEntity.ok(
            AffinityAnswersResponse.from(
                answerService.getMyAnswers(userId)
            )
        )

    @PatchMapping
    fun patchMyAffinityAnswers(
        @CurrentUserId userId: UUID,
        @Valid
        @RequestBody request: PatchAffinityAnswersRequest
    ): ResponseEntity<AffinityAnswersResponse> {
        legalComplianceService.requireCurrentRequirementsSatisfied(userId)

        val answers =
            answerService.patchMyAnswers(
                userId = userId,
                patches = request.answers.map {
                    AffinityAnswerPatch(
                        questionId = it.questionId,
                        answerCode = it.answerCode
                    )
                }
            )

        return ResponseEntity.ok(AffinityAnswersResponse.from(answers))
    }

    @DeleteMapping("/{questionId}")
    fun deleteMyAffinityAnswer(
        @CurrentUserId userId: UUID,
        @PathVariable questionId: String
    ): ResponseEntity<AffinityAnswersResponse> {
        legalComplianceService.requireCurrentRequirementsSatisfied(userId)

        return ResponseEntity.ok(
            AffinityAnswersResponse.from(
                answerService.deleteMyAnswer(
                    userId = userId,
                    questionId = questionId
                )
            )
        )
    }
}
