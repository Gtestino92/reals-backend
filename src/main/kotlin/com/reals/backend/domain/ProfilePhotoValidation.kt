package com.reals.backend.domain


data class ProfilePhotoValidationResult(
    val isPersonPhoto: Boolean,
    val isFullBody: Boolean,
    val status: PhotoValidationStatus
)

class ProfilePhotoUploadValidationRequest(
    val contentType: String,
    val bytes: ByteArray
)

data class ProfilePhotoExternalValidationRequest(
    val url: String
)

