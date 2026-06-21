package com.reals.backend.integration

import com.reals.backend.config.security.authentication.FirebasePrincipal
import com.reals.backend.config.security.SecurityRoles
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@AutoConfigureMockMvc
abstract class ControllerIT : BaseIT() {

    @Autowired
    protected lateinit var mockMvc: MockMvc

    @Autowired
    protected lateinit var objectMapper: ObjectMapper

    protected val jsonContentType: MediaType = MediaType.APPLICATION_JSON

    protected fun authenticatedAs(userId: UUID): RequestPostProcessor =
        SecurityMockMvcRequestPostProcessors.authentication(
            UsernamePasswordAuthenticationToken(
                userId.toString(),
                null,
                listOf(SimpleGrantedAuthority(SecurityRoles.ROLE_USER))
            )
        )

    protected fun authenticatedAsAdmin(userId: UUID): RequestPostProcessor =
        SecurityMockMvcRequestPostProcessors.authentication(
            UsernamePasswordAuthenticationToken(
                userId.toString(),
                null,
                listOf(
                    SimpleGrantedAuthority(SecurityRoles.ROLE_USER),
                    SimpleGrantedAuthority(SecurityRoles.ROLE_ADMIN)
                )
            )
        )

    protected fun authenticatedWithFirebase(
        firebaseUid: String,
        email: String?
    ): RequestPostProcessor =
        SecurityMockMvcRequestPostProcessors.authentication(
            UsernamePasswordAuthenticationToken(
                FirebasePrincipal(
                    uid = firebaseUid,
                    email = email
                ),
                null,
                listOf(SimpleGrantedAuthority(SecurityRoles.ROLE_FIREBASE_AUTHENTICATED))
            )
        )

    protected fun jsonBody(value: Any): String =
        objectMapper.writeValueAsString(value)
}
