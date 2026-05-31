package com.reals.backend.config.security.currentuser

/**
 * Marks a controller parameter to be resolved with the authenticated user id.
 *
 * Local-nodb and local-postgres use DevAutoAuthFilter.
 * Local-firebase/dev/prod should use FirebaseTokenFilter.
 * Both filters set the SecurityContext principal to the internal user UUID
 * string, keeping controllers independent from the auth provider.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class CurrentUserId
