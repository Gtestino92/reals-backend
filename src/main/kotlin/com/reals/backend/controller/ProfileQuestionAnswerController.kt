package com.reals.backend.controller

import com.reals.backend.config.security.currentuser.CurrentUserId
import com.reals.backend.controller.dto.ProfileQuestionAnswersResponse
import com.reals.backend.controller.dto.UpdateProfileQuestionSelectionsRequest
import com.reals.backend.controller.dto.UpsertProfileQuestionAnswerRequest
import com.reals.backend.service.LegalComplianceService
import com.reals.backend.service.profilequestion.ProfileQuestionAnswerService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@Validated
class ProfileQuestionAnswerController(
    private val answerService: ProfileQuestionAnswerService,
    private val legalComplianceService: LegalComplianceService
) {
    @GetMapping("/api/me/profile/question-answers")
    fun getMyProfileQuestionAnswers(
        @CurrentUserId userId: UUID
    ): ResponseEntity<ProfileQuestionAnswersResponse> =
        ResponseEntity.ok(
            ProfileQuestionAnswersResponse.from(
                answerService.getMyAnswers(userId)
            )
        )

    @PutMapping("/api/me/profile/question-answers/{questionId}")
    fun upsertMyProfileQuestionAnswer(
        @CurrentUserId userId: UUID,
        @PathVariable questionId: String,
        @Valid
        @RequestBody request: UpsertProfileQuestionAnswerRequest
    ): ResponseEntity<ProfileQuestionAnswersResponse> {
        legalComplianceService.requireCurrentRequirementsSatisfied(userId)

        return ResponseEntity.ok(
            ProfileQuestionAnswersResponse.from(
                answerService.upsertMyAnswer(
                    userId = userId,
                    questionId = questionId,
                    answerText = request.answer
                )
            )
        )
    }

    @DeleteMapping("/api/me/profile/question-answers/{questionId}")
    fun deleteMyProfileQuestionAnswer(
        @CurrentUserId userId: UUID,
        @PathVariable questionId: String
    ): ResponseEntity<ProfileQuestionAnswersResponse> {
        legalComplianceService.requireCurrentRequirementsSatisfied(userId)

        return ResponseEntity.ok(
            ProfileQuestionAnswersResponse.from(
                answerService.deleteMyAnswer(
                    userId = userId,
                    questionId = questionId
                )
            )
        )
    }

    @PutMapping("/api/me/profile/question-selections")
    fun replaceMyProfileQuestionSelections(
        @CurrentUserId userId: UUID,
        @Valid
        @RequestBody request: UpdateProfileQuestionSelectionsRequest
    ): ResponseEntity<ProfileQuestionAnswersResponse> {
        legalComplianceService.requireCurrentRequirementsSatisfied(userId)

        return ResponseEntity.ok(
            ProfileQuestionAnswersResponse.from(
                answerService.replaceMySelections(
                    userId = userId,
                    orderedQuestionIds = request.questionIds
                )
            )
        )
    }
}
