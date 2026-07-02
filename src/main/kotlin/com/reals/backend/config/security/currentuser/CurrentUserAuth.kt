package com.reals.backend.config.security.currentuser

/**
 * Marks a controller parameter to be resolved with the authenticated user's
 * backend auth context.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class CurrentUserAuth
