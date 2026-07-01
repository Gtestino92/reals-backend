package com.reals.backend.config

import com.reals.backend.config.security.currentuser.CurrentUserAuthArgumentResolver
import com.reals.backend.config.security.currentuser.CurrentUserIdArgumentResolver
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebMvcConfig(
    private val currentUserAuthArgumentResolver: CurrentUserAuthArgumentResolver,
    private val currentUserIdArgumentResolver: CurrentUserIdArgumentResolver
) : WebMvcConfigurer {

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(currentUserAuthArgumentResolver)
        resolvers.add(currentUserIdArgumentResolver)
    }
}
