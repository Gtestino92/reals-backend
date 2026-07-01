package com.reals.backend.config.security.currentuser

/**
 * Marks a controller parameter to be resolved with the authenticated user id.
 *
 * Local-nodb and local-postgres use DevAutoAuthFilter.
 * Local-firebase/dev/prod should use FirebaseTokenFilter.
 * Filters set the SecurityContext principal to either the internal user UUID
 * string or CurrentUserAuthContext, keeping controllers independent from the
 * auth provider when they only need the user id.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class CurrentUserId
