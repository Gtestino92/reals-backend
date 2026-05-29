package com.reals.backend.config

import org.springframework.core.MethodParameter
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import java.util.UUID

/**
 * Resolves controller parameters annotated with @CurrentUserId by reading the
 * authenticated principal from the SecurityContext.
 *
 * Auth filters must set the principal as an internal user UUID string.
 */
@Component
class CurrentUserIdArgumentResolver :
    HandlerMethodArgumentResolver {

    override fun supportsParameter(
        parameter: MethodParameter
    ): Boolean =
        parameter.hasParameterAnnotation(CurrentUserId::class.java)
                && parameter.parameterType == UUID::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?
    ): UUID {

        val principal =
            SecurityContextHolder.getContext()
                .authentication?.principal
                ?: error("No authenticated principal found in SecurityContext")

        return UUID.fromString(principal as String)
    }
}
