package com.reals.backend.controller

import com.reals.backend.config.security.currentuser.CurrentUserId
import com.reals.backend.controller.dto.NotificationPreferenceRequest
import com.reals.backend.controller.dto.NotificationPreferenceResponse
import com.reals.backend.service.NotificationPreferenceService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class MeNotificationPreferenceController(
    private val notificationPreferenceService: NotificationPreferenceService
) {

    @GetMapping("/api/me/notification-preferences")
    fun getNotificationPreferences(
        @CurrentUserId userId: UUID
    ): ResponseEntity<NotificationPreferenceResponse> =
        ResponseEntity.ok(
            NotificationPreferenceResponse.from(
                notificationPreferenceService.preferencesFor(userId)
            )
        )

    @PutMapping("/api/me/notification-preferences")
    fun updateNotificationPreferences(
        @CurrentUserId userId: UUID,
        @Valid
        @RequestBody request: NotificationPreferenceRequest
    ): ResponseEntity<NotificationPreferenceResponse> =
        ResponseEntity.ok(
            NotificationPreferenceResponse.from(
                notificationPreferenceService.updatePreferences(
                    userId = userId,
                    input = request.toSettings()
                )
            )
        )
}
