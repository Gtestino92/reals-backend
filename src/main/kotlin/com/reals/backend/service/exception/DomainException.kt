package com.reals.backend.service.exception

enum class DomainErrorCode {
    ACTIVE_MATCH_LIMIT_REACHED,
    ACTIVE_PENALTY,
    INVALID_MATCH_FILTERS,
    INVALID_PROFILE_BIRTH_DATE,
    INVALID_SEARCH_LOCATION,
    PHOTO_POSITION_INVALID,
    PHOTO_POSITION_OCCUPIED,
    PHOTO_URL_INVALID,
    PROFILE_ALREADY_EXISTS,
    PROFILE_FULL_BODY_PHOTO_REQUIRED,
    PROFILE_NOT_ACTIVE,
    PROFILE_NOT_ACTIVATABLE,
    PROFILE_PERSON_PHOTO_REQUIRED,
    PROFILE_PHOTO_LIMIT_REACHED,
    PROFILE_PHOTOS_REQUIRED,
    PROFILE_REQUIRED,
    USER_NOT_FOUND,
    USER_NOT_ACTIVE,
}

sealed class DomainException(
    val code: DomainErrorCode,
    message: String
) : RuntimeException(message)

class DomainBadRequestException(
    code: DomainErrorCode,
    message: String
) : DomainException(code, message)

class DomainConflictException(
    code: DomainErrorCode,
    message: String
) : DomainException(code, message)
