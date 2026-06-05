package com.reals.backend.domain

data class StoredObject(
    val bucket: String,
    val key: String,
    val url: String,
    val contentType: String,
    val sizeBytes: Long
)