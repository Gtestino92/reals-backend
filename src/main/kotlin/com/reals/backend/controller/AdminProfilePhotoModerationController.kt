package com.reals.backend.controller

import com.reals.backend.config.security.currentuser.CurrentUserId
import com.reals.backend.controller.dto.AdminPhotoModerationResolutionRequest
import com.reals.backend.controller.dto.AdminProfilePhotoModerationReviewResponse
import com.reals.backend.service.photo.ProfilePhotoModerationReviewService
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
@RequestMapping("/api/admin/profile-photos")
class AdminProfilePhotoModerationController(
    private val profilePhotoModerationReviewService: ProfilePhotoModerationReviewService
) {

    @GetMapping("/review")
    fun listReviewQueue(): ResponseEntity<List<AdminProfilePhotoModerationReviewResponse>> =
        ResponseEntity.ok(
            profilePhotoModerationReviewService.listNeedsReview()
                .map { AdminProfilePhotoModerationReviewResponse.from(it) }
        )

    @PostMapping("/{photoId}/moderation")
    fun resolveModeration(
        @PathVariable photoId: UUID,
        @CurrentUserId adminUserId: UUID,
        @Valid
        @RequestBody request: AdminPhotoModerationResolutionRequest
    ): ResponseEntity<AdminProfilePhotoModerationReviewResponse> =
        ResponseEntity.ok(
            AdminProfilePhotoModerationReviewResponse.from(
                profilePhotoModerationReviewService.resolve(
                    photoId = photoId,
                    adminUserId = adminUserId,
                    decision = requireNotNull(request.decision),
                    notes = request.normalizedNotes()
                )
            )
        )
}
