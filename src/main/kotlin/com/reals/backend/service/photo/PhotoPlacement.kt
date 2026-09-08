package com.reals.backend.service.photo

import java.util.UUID

data class PhotoPlacement(
    val photoId: UUID,
    val position: Int
)
