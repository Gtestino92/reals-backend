package com.reals.backend.controller.dto

import com.reals.backend.domain.PushPlatform
import com.reals.backend.service.NotificationPreferenceSettings
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

data class RegisterPushTokenRequest(
    @field:NotBlank
    val token: String,
    val platform: PushPlatform
)

data class RegisterPushTokenResponse(
    val registered: Boolean
)

data class NotificationPreferenceRequest(
    @field:NotNull
    val activityEnabled: Boolean?,

    @field:NotNull
    val remindersEnabled: Boolean?,

    @field:NotNull
    val availabilityEnabled: Boolean?
) {
    fun toSettings(): NotificationPreferenceSettings =
        NotificationPreferenceSettings(
            activityEnabled = activityEnabled!!,
            remindersEnabled = remindersEnabled!!,
            availabilityEnabled = availabilityEnabled!!
        )
}

data class NotificationPreferenceResponse(
    val activityEnabled: Boolean,
    val remindersEnabled: Boolean,
    val availabilityEnabled: Boolean
) {
    companion object {
        fun from(settings: NotificationPreferenceSettings): NotificationPreferenceResponse =
            NotificationPreferenceResponse(
                activityEnabled = settings.activityEnabled,
                remindersEnabled = settings.remindersEnabled,
                availabilityEnabled = settings.availabilityEnabled
            )
    }
}
