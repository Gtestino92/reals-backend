package com.reals.backend.controller

import com.reals.backend.config.CurrentUserId
import com.reals.backend.controller.dto.*
import com.reals.backend.service.ProfileService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.*

@RestController
@RequestMapping("/api/profiles")
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
        return ResponseEntity.ok(
            ProfileResponse.from(profile, photos.size)
        )
    }

    @GetMapping("/me")
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


    @PatchMapping("/me")
    fun updateMyProfile(
        @CurrentUserId userId: UUID,
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

    @PostMapping("/me/activate")
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

    /**
     * Positions 1-9 are valid. Each position can only be occupied once
     * TODO: the isPersonPhoto and isFullBody should be validated with other services!
     */
    @PostMapping("/{profileId}/photos")
    fun addPhoto(
        @PathVariable profileId: UUID,
        @RequestBody request: AddPhotoRequest
    ): ResponseEntity<PhotoResponse> {

        val photo = profileService.addPhoto(
            profileId = profileId,
            url = request.url,
            position = request.position,
            isPersonPhoto = request.isPersonPhoto,
            isFullBody = request.isFullBody
        )

        return ResponseEntity.ok(
            PhotoResponse.from(photo)
        )
    }

    @GetMapping("/{profileId}/photos")
    fun getPhotos(
        @PathVariable profileId: UUID
    ): ResponseEntity<List<PhotoResponse>> {

        val photos = profileService.getPhotos(profileId = profileId)
            .sortedBy { it.position }
            .map { PhotoResponse.from(it) }

        return ResponseEntity.ok(photos)
    }

    /**
     * Validates photos and sets profile status to ACTIVE
     * Requirements are configurable via profile.photos.* properties
     * Defaults (prod): exactly 9 photos, min 3 person photos, min 1 full body
     * Local (local-nodb): exactly 4 photos, min 1 and 1
     * Only ACTIVE profiles can enter matchmaking
     */
    @PostMapping("{profileId}/activate")
    fun activateProfile(@PathVariable profileId: UUID): ResponseEntity<ProfileResponse> {
        val profile = profileService.activateProfile(profileId)
        val photos = profileService.getPhotos(profile.id)
        return ResponseEntity.ok(ProfileResponse.from(profile, photos.size))
    }

    /** Partially updates editable text fields
     * only non-null fields in the req body are applied
     * birthDate and gender are not editable after profile creation
     *
     */
    @PatchMapping("/{profileId}")
    fun updateProfile(
        @PathVariable profileId: UUID,
        @RequestBody request: UpdateProfileRequest
    ): ResponseEntity<ProfileResponse> {
        val profile = profileService.updateProfile(
            profileId = profileId,
            displayName = request.displayName,
            bio = request.bio,
            city = request.city,
            country = request.country,
            intention = request.intention,
            lookingForGender = request.lookingForGender
        )
        val photos = profileService.getPhotos(profile.id)
        return ResponseEntity.ok(ProfileResponse.from(profile, photos.size))
    }

    @DeleteMapping("/{profileId}/{position}")
    fun deletePhoto(
        @PathVariable profileId: UUID,
        @PathVariable position: Int
    ): ResponseEntity<ProfileResponse> {

        val profile = profileService.deletePhoto(
            profileId = profileId,
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

    // REPLACE PHOTO
    @PutMapping("/{profileId}/photos/{position}")
    fun replacePhoto(
        @PathVariable profileId: UUID,
        @PathVariable position: Int,
        @RequestBody request: ReplacePhotoRequest
    ): ResponseEntity<PhotoResponse> {
        val photo = profileService.replacePhoto(
            profileId = profileId,
            position = position,
            url = request.url,
            isPersonPhoto = request.isPersonPhoto,
            isFullBody = request.isFullBody
        )
        return ResponseEntity.ok(PhotoResponse.from(photo))
    }
}