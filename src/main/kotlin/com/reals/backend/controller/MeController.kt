package com.reals.backend.controller

import com.reals.backend.config.security.authentication.FirebasePrincipal
import com.reals.backend.config.security.currentuser.CurrentUserAuthContext
import com.reals.backend.config.security.currentuser.CurrentUserId
import com.reals.backend.controller.dto.HomePendingStateResponse
import com.reals.backend.controller.dto.HomeResponse
import com.reals.backend.controller.dto.HomeStatusResponse
import com.reals.backend.service.HomeStatusService
import com.reals.backend.controller.dto.UserResponse
import com.reals.backend.service.MeHomeService
import com.reals.backend.service.UserService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.util.UUID

@RestController
class MeController(
    private val userService: UserService,
    private val meHomeService: MeHomeService,
    private val homeStatusService: HomeStatusService
) {

    @GetMapping("/api/me")
    fun getMe(
        @CurrentUserId userId: UUID
    ): ResponseEntity<UserResponse> {
        val user = userService.findByIdOrThrow(
            userId = userId
        )
        return ResponseEntity.ok(
            UserResponse.from(user)
        )
    }

    @GetMapping("/api/me/home")
    fun getHome(
        @CurrentUserId userId: UUID
    ): ResponseEntity<HomeResponse> {
        val statusBefore = homeStatusService.getOrCreateStatus(userId = userId)
        val projection = meHomeService.getHomeProjection(userId = userId)
        homeStatusService.reconcileAfterFullHomeIfVersionStill(
            userId = userId,
            expectedVersion = statusBefore.version,
            nextRefreshAt = projection.nextRefreshAt
        )
        return ResponseEntity.ok(projection.home)
    }

    @GetMapping("/api/me/home/status")
    fun getHomeStatus(
        @CurrentUserId userId: UUID
    ): ResponseEntity<HomeStatusResponse> {
        val status = homeStatusService.getOrCreateStatus(userId = userId)
        return ResponseEntity.ok(
            HomeStatusResponse(
                version = status.version,
                dirty = status.dirty,
                nextRefreshAt = status.nextRefreshAt,
                serverTime = OffsetDateTime.now()
            )
        )
    }

    @GetMapping("/api/me/home/pending")
    fun getHomePending(
        @CurrentUserId userId: UUID
    ): ResponseEntity<HomePendingStateResponse> =
        ResponseEntity.ok(
            meHomeService.getPendingHomeState(userId = userId)
        )

    @DeleteMapping("/api/me")
    fun deleteMe(
        @CurrentUserId userId: UUID
    ): ResponseEntity<Void> {
        userService.deleteUser(
            userId = userId
        )
        return ResponseEntity.ok().build()
    }

    @PostMapping("/api/me/reactivation")
    fun reactivateMe(
        @CurrentUserId userId: UUID
    ): ResponseEntity<UserResponse> {
        val user = userService.reactivateUser(
            userId = userId
        )
        return ResponseEntity.ok(
            UserResponse.from(user)
        )
    }

    @PostMapping("/api/me/provision")
    fun provisionMe(
        authentication: Authentication
    ): ResponseEntity<UserResponse> {
        val principal = authentication.principal

        if (principal is String) {
            val user = userService.findByIdOrThrow(
                userId = UUID.fromString(principal)
            )
            return ResponseEntity.ok(
                UserResponse.from(user)
            )
        }

        if (principal is CurrentUserAuthContext) {
            val user = userService.findByIdOrThrow(
                userId = principal.userId
            )
            return ResponseEntity.ok(
                UserResponse.from(user)
            )
        }

        val firebasePrincipal = principal as? FirebasePrincipal
            ?: throw IllegalStateException("Firebase principal is required")

        val user = userService.provisionFromFirebase(
            firebaseUid = firebasePrincipal.uid,
            email = firebasePrincipal.email,
            emailVerified = firebasePrincipal.emailVerified
        )

        return ResponseEntity.status(HttpStatus.CREATED).body(
            UserResponse.from(user)
        )
    }
}
