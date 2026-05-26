package com.reals.backend.config

/**
 * Marks a controller parameter to be resolved with the authenticated user id.
 *
 * In local-nodb: resolved from the principal injected by DevAutoAuthFilter.
 *
 * In prod: will be resolved from the JWT claim (PENDING.md #9) — only the
 * CurrentUserIdArgumentResolver needs to change, all controllers stay the same.
 *
 * Usage:
 *   fun myEndpoint(@CurrentUserId userId: UUID): ResponseEntity<...>
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class CurrentUserId
