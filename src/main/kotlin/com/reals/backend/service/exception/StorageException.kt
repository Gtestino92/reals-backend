package com.reals.backend.service.exception

class ObjectStorageException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)