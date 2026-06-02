package com.reals.backend.controller

import com.reals.backend.config.security.currentuser.CurrentUserId
import com.reals.backend.controller.dto.*
import com.reals.backend.service.ProfileService
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import java.util.*

@RestController
@RequestMapping("/api/me/profile")
@Validated
class ProfileController(
    private val profileService: ProfileService
) {


    /**
     * Creates a RAFT profile for a user
     * A user can only have one profile
     */
    @PostMapping
    fun createProfile(
        @CurrentUserId userId: UUID,
        @Valid
        @RequestBody request: CreateProfileRequest
    ): ResponseEntity<ProfileResponse> {

        val profile = profileService.createProfile(
            userId = userId,
            displayName = request.displayName,
            birthDate = request.birthDate,
            gender = request.gender,
            lookingForGender = request.lookingForGender,
            intention = request.intention,
            city = request.city,
            country = request.country,
            bio = request.bio
        )
        val photos = profileService.getPhotos(profile.id)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            ProfileResponse.from(profile, photos.size)
        )
    }

    @GetMapping
    fun getMyProfile(
        @CurrentUserId userId: UUID
    ): ResponseEntity<ProfileResponse> {

        val profile = profileService.findByUserId(userId)
            ?: return ResponseEntity.notFound().build()

        val photos = profileService.getPhotos(profile.id)

        return ResponseEntity.ok(
            ProfileResponse.from(
                profile = profile,
                photoCount = photos.size
            )
        )
    }


    @PatchMapping
    fun updateMyProfile(
        @CurrentUserId userId: UUID,
        @Valid
        @RequestBody request: UpdateProfileRequest
    ): ResponseEntity<ProfileResponse> {

        val profile = profileService.findByUserId(userId)
            ?: throw NoSuchElementException(
                "Profile not found for user: $userId"
            )

        val updated = profileService.updateProfile(
            profileId = profile.id,
            displayName = request.displayName,
            bio = request.bio,
            city = request.city,
            country = request.country,
            intention = request.intention,
            lookingForGender = request.lookingForGender
        )

        val photos = profileService.getPhotos(updated.id)

        return ResponseEntity.ok(
            ProfileResponse.from(
                profile = updated,
                photoCount = photos.size
            )
        )
    }

    @PostMapping("/activation")
    fun activateMyProfile(
        @CurrentUserId userId: UUID
    ): ResponseEntity<ProfileResponse> {

        val profile = profileService.findByUserId(userId)
            ?: throw NoSuchElementException(
                "Profile not found for user: $userId"
            )

        val activated = profileService.activateProfile(
            profileId = profile.id
        )

        val photos = profileService.getPhotos(activated.id)

        return ResponseEntity.ok(
            ProfileResponse.from(
                profile = activated,
                photoCount = photos.size
            )
        )
    }

    @PostMapping("/identity-verification")
    fun verifyMyIdentity(
        @CurrentUserId userId: UUID
    ): ResponseEntity<ProfileResponse> {

        val profile = profileService.findByUserId(userId)
            ?: throw NoSuchElementException(
                "Profile not found for user: $userId"
            )

        val verified = profileService.verifyIdentity(
            profileId = profile.id
        )

        val photos = profileService.getPhotos(verified.id)

        return ResponseEntity.ok(
            ProfileResponse.from(
                profile = verified,
                photoCount = photos.size
            )
        )
    }

    /**
     * Positions 1-9 are valid. Each position can only be occupied once
     * Semantic photo classification is delegated to ProfilePhotoValidationService.
     */
    @PostMapping("/photos")
    fun addPhoto(
        @CurrentUserId userId: UUID,
        @Valid
        @RequestBody request: AddPhotoRequest
    ): ResponseEntity<PhotoResponse> {
        val profile = profileService.findByUserId(userId)
            ?: throw NoSuchElementException(
                "Profile not found for user: $userId"
            )

        val photo = profileService.addPhoto(
            profileId = profile.id,
            url = request.url,
            position = request.position,
            isPersonPhoto = request.isPersonPhoto,
            isFullBody = request.isFullBody
        )

        return ResponseEntity.status(HttpStatus.CREATED).body(
            PhotoResponse.from(photo)
        )
    }

    @GetMapping("/photos")
    fun getPhotos(
        @CurrentUserId userId: UUID
    ): ResponseEntity<List<PhotoResponse>> {
        val profile = profileService.findByUserId(userId)
            ?: throw NoSuchElementException(
                "Profile not found for user: $userId"
            )

        val photos = profileService.getPhotos(profileId = profile.id)
            .sortedBy { it.position }
            .map { PhotoResponse.from(it) }

        return ResponseEntity.ok(photos)
    }

    @DeleteMapping("/photos/{position}")
    fun deletePhoto(
        @CurrentUserId userId: UUID,
        @Min(1)
        @PathVariable position: Int
    ): ResponseEntity<ProfileResponse> {
        val existing = profileService.findByUserId(userId)
            ?: throw NoSuchElementException(
                "Profile not found for user: $userId"
            )

        val profile = profileService.deletePhoto(
            profileId = existing.id,
            position = position
        )

        val photos = profileService.getPhotos(profile.id)

        return ResponseEntity.ok(
            ProfileResponse.from(
                profile = profile,
                photoCount = photos.size
            )
        )
    }

    @PutMapping("/photos/{position}")
    fun replacePhoto(
        @CurrentUserId userId: UUID,
        @Min(1)
        @PathVariable position: Int,
        @Valid
        @RequestBody request: ReplacePhotoRequest
    ): ResponseEntity<PhotoResponse> {
        val profile = profileService.findByUserId(userId)
            ?: throw NoSuchElementException(
                "Profile not found for user: $userId"
            )

        val photo = profileService.replacePhoto(
            profileId = profile.id,
            position = position,
            url = request.url,
            isPersonPhoto = request.isPersonPhoto,
            isFullBody = request.isFullBody
        )
        return ResponseEntity.ok(PhotoResponse.from(photo))
    }
}
