package com.reals.backend.controller

import com.reals.backend.service.exception.DomainBadRequestException
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.exception.DomainException
import com.reals.backend.service.exception.DomainNotFoundException
import com.reals.backend.service.exception.ObjectStorageException
import com.reals.backend.service.ProfilePhotoUploadBusyException
import jakarta.validation.ConstraintViolationException
import org.hibernate.exception.JDBCConnectionException
import org.slf4j.LoggerFactory
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.jdbc.CannotGetJdbcConnectionException
import org.springframework.security.access.AccessDeniedException
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.support.MissingServletRequestPartException
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.sql.SQLException
import java.sql.SQLTransientConnectionException

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    data class ErrorResponse(
        val code: String,
        val error: String,
        val message: String?
    )

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException
    ): ResponseEntity<ErrorResponse> {
        val code = ex.chatValidationErrorCode() ?: "VALIDATION_ERROR"

        return ResponseEntity.badRequest()
            .body(
                ErrorResponse(
                    code = code,
                    error = "Bad Request",
                    message = ex.bindingResult.fieldErrors
                        .joinToString("; ") { it.toValidationMessage() }
                        .ifBlank { "Request validation failed" }
                )
            )
    }

    @ExceptionHandler(HandlerMethodValidationException::class)
    fun handleHandlerMethodValidation(
        ex: HandlerMethodValidationException
    ): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest()
            .body(
                ErrorResponse(
                    code = if (ex.message.contains("position")) {
                        DomainErrorCode.PHOTO_POSITION_INVALID.name
                    } else {
                        "VALIDATION_ERROR"
                    },
                    error = "Bad Request",
                    message = "Request validation failed"
                )
            )

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(
        ex: ConstraintViolationException
    ): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest()
            .body(
                ErrorResponse(
                    code = if (ex.constraintViolations.any { it.propertyPath.toString().contains("position") }) {
                        DomainErrorCode.PHOTO_POSITION_INVALID.name
                    } else {
                        "VALIDATION_ERROR"
                    },
                    error = "Bad Request",
                    message = ex.constraintViolations
                        .joinToString("; ") { "${it.propertyPath}: ${it.message}" }
                        .ifBlank { "Request validation failed" }
                )
            )

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableMessage(
        ex: HttpMessageNotReadableException
    ): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest()
            .body(
                ErrorResponse(
                    code = "MALFORMED_REQUEST",
                    error = "Bad Request",
                    message = "Malformed request body or invalid field value"
                )
            )

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(
        ex: MethodArgumentTypeMismatchException
    ): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest()
            .body(
                ErrorResponse(
                    code = "INVALID_ARGUMENT",
                    error = "Bad Request",
                    message = "Invalid value for ${ex.name}"
                )
            )

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun handleUnsupportedMediaType(
        ex: HttpMediaTypeNotSupportedException
    ): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
            .body(
                ErrorResponse(
                    code = "UNSUPPORTED_MEDIA_TYPE",
                    error = "Unsupported Media Type",
                    message = "Content type is not supported for this endpoint"
                )
            )

    @ExceptionHandler(MissingServletRequestPartException::class)
    fun handleMissingServletRequestPart(
        ex: MissingServletRequestPartException
    ): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest()
            .body(
                ErrorResponse(
                    code = if (ex.requestPartName == "file") {
                        DomainErrorCode.INVALID_PROFILE_PHOTO.name
                    } else {
                        "VALIDATION_ERROR"
                    },
                    error = "Bad Request",
                    message = "Required multipart part is missing"
                )
            )

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingServletRequestParameter(
        ex: MissingServletRequestParameterException
    ): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest()
            .body(
                ErrorResponse(
                    code = if (ex.parameterName == "position") {
                        DomainErrorCode.PHOTO_POSITION_INVALID.name
                    } else {
                        "VALIDATION_ERROR"
                    },
                    error = "Bad Request",
                    message = "Required request parameter is missing"
                )
            )

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrityViolation(
        ex: DataIntegrityViolationException
    ): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(
                ErrorResponse(
                    code = "DATA_INTEGRITY_CONFLICT",
                    error = "Conflict",
                    message = "Data integrity constraint violation"
                )
            )

    @ExceptionHandler(OptimisticLockingFailureException::class)
    fun handleOptimisticLockingFailure(
        ex: OptimisticLockingFailureException
    ): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(
                ErrorResponse(
                    code = "CONCURRENT_MODIFICATION",
                    error = "Conflict",
                    message = "Resource was modified concurrently. Please retry."
                )
            )

    @ExceptionHandler(
        CannotGetJdbcConnectionException::class,
        DataAccessResourceFailureException::class,
        SQLTransientConnectionException::class
    )
    fun handleDatabaseUnavailable(
        ex: Exception
    ): ResponseEntity<ErrorResponse> {
        log.warn("Database unavailable while processing request: {}", ex.message)

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(
                ErrorResponse(
                    code = "DATABASE_UNAVAILABLE",
                    error = "Service Unavailable",
                    message = "Database is temporarily unavailable. Please retry later."
                )
            )
    }

    @ExceptionHandler(
        CannotAcquireLockException::class,
        PessimisticLockingFailureException::class
    )
    fun handleTransientConcurrencyFailure(
        ex: Exception
    ): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(
                ErrorResponse(
                    code = "TRANSIENT_CONCURRENCY_CONFLICT",
                    error = "Conflict",
                    message = "Resource is temporarily busy. Please retry."
                )
            )

    @ExceptionHandler(DomainException::class)
    fun handleDomainException(
        ex: DomainException
    ): ResponseEntity<ErrorResponse> {
        val status = when (ex) {
            is DomainBadRequestException -> HttpStatus.BAD_REQUEST
            is DomainConflictException -> HttpStatus.CONFLICT
            is DomainNotFoundException -> HttpStatus.NOT_FOUND
        }

        return ResponseEntity.status(status)
            .body(
                ErrorResponse(
                    code = ex.code.name,
                    error = status.reasonPhrase,
                    message = ex.message
                )
            )
    }

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(
        ex: AccessDeniedException
    ): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(
                ErrorResponse(
                    code = "ACCESS_DENIED",
                    error = "Forbidden",
                    message = ex.message
                )
            )

    @ExceptionHandler(ObjectStorageException::class)
    fun handleObjectStorageException(
        ex: ObjectStorageException
    ): ResponseEntity<ErrorResponse> {
        log.warn("Object storage failure while processing request: {}", ex.message)

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(
                ErrorResponse(
                    code = DomainErrorCode.PROFILE_PHOTO_UPLOAD_FAILED.name,
                    error = "Bad Gateway",
                    message = "Profile photo upload failed. Please retry."
                )
            )
    }

    @ExceptionHandler(ProfilePhotoUploadBusyException::class)
    fun handleProfilePhotoUploadBusy(
        ex: ProfilePhotoUploadBusyException
    ): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, ex.retryAfterSeconds.toString())
            .body(
                ErrorResponse(
                    code = DomainErrorCode.PROFILE_PHOTO_UPLOAD_BUSY.name,
                    error = "Service Unavailable",
                    message = "Profile photo upload capacity is temporarily exhausted. Please retry later."
                )
            )

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(
        ex: NoSuchElementException
    ): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(
                ErrorResponse(
                    code = "RESOURCE_NOT_FOUND",
                    error = "Not Found",
                    message = ex.message
                )
            )

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResourceFound(
        ex: NoResourceFoundException
    ): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(
                ErrorResponse(
                    code = "RESOURCE_NOT_FOUND",
                    error = "Not Found",
                    message = "Resource not found"
                )
            )

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(
        ex: IllegalArgumentException
    ): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest()
            .body(
                ErrorResponse(
                    code = "INVALID_ARGUMENT",
                    error = "Bad Request",
                    message = ex.message
                )
            )

    // Domain rule violations: check() / require() / illegal state
    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(
        ex: IllegalStateException
    ): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(
                ErrorResponse(
                    code = "DOMAIN_CONFLICT",
                    error = "Conflict",
                    message = ex.message
                )
            )

    @ExceptionHandler(Exception::class)
    fun handleGeneric(
        ex: Exception
    ): ResponseEntity<ErrorResponse> {
        if (ex.isDatabaseUnavailable()) {
            return handleDatabaseUnavailable(ex)
        }

        log.error("Unhandled exception while processing request", ex)

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(
                ErrorResponse(
                    code = "INTERNAL_ERROR",
                    error = "Internal Server Error",
                    message = "Unexpected server error"
                )
            )
    }

    private fun FieldError.toValidationMessage(): String =
        "$field: ${defaultMessage ?: "invalid value"}"

    private fun MethodArgumentNotValidException.chatValidationErrorCode(): String? {
        val chatMessageRequestObjects =
            setOf(
                "sendMessageRequest",
                "chatExitRequestCreateRequest",
                "chatCancellationRequest",
                "chatSafetyCancellationRequest"
            )

        return if (
            bindingResult.objectName in chatMessageRequestObjects &&
            bindingResult.fieldErrors.any { it.field == "content" || it.field == "details" }
        ) {
            DomainErrorCode.CHAT_MESSAGE_INVALID.name
        } else {
            null
        }
    }

    private fun Throwable.isDatabaseUnavailable(): Boolean {
        var current: Throwable? = this

        while (current != null) {
            when (current) {
                is CannotGetJdbcConnectionException,
                is DataAccessResourceFailureException,
                is SQLTransientConnectionException,
                is JDBCConnectionException -> return true
                is SQLException ->
                    if (current.sqlState?.startsWith(CONNECTION_EXCEPTION_SQL_STATE_CLASS) == true) {
                        return true
                    }
            }

            current = current.cause
        }

        return false
    }

    private companion object {
        const val CONNECTION_EXCEPTION_SQL_STATE_CLASS = "08"
    }
}
