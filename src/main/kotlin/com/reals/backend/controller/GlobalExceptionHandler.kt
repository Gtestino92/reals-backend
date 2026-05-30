package com.reals.backend.controller

import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

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
    ): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest()
            .body(
                ErrorResponse(
                    code = "VALIDATION_ERROR",
                    error = "Bad Request",
                    message = ex.bindingResult.fieldErrors
                        .joinToString("; ") { it.toValidationMessage() }
                        .ifBlank { "Request validation failed" }
                )
            )

    @ExceptionHandler(HandlerMethodValidationException::class)
    fun handleHandlerMethodValidation(
        ex: HandlerMethodValidationException
    ): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest()
            .body(
                ErrorResponse(
                    code = "VALIDATION_ERROR",
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
                    code = "VALIDATION_ERROR",
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
}
