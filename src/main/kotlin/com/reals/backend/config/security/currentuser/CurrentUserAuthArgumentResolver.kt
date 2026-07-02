package com.reals.backend.config.security.currentuser

import org.springframework.core.MethodParameter
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import java.util.UUID

@Component
class CurrentUserAuthArgumentResolver :
    HandlerMethodArgumentResolver {

    override fun supportsParameter(
        parameter: MethodParameter
    ): Boolean =
        parameter.hasParameterAnnotation(CurrentUserAuth::class.java)
                && parameter.parameterType == CurrentUserAuthContext::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?
    ): CurrentUserAuthContext {
        val principal =
            SecurityContextHolder.getContext()
                .authentication?.principal
                ?: error("No authenticated principal found in SecurityContext")

        return when (principal) {
            is CurrentUserAuthContext -> principal
            is String -> CurrentUserAuthContext(
                userId = UUID.fromString(principal),
                firebaseUid = null,
                email = null,
                emailVerified = true
            )
            else -> error("Unsupported authenticated principal type: ${principal::class.qualifiedName}")
        }
    }
}
