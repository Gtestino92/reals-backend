package com.reals.backend.controller

import com.reals.backend.config.security.currentuser.CurrentUserAuth
import com.reals.backend.config.security.currentuser.CurrentUserAuthContext
import com.reals.backend.config.security.currentuser.CurrentUserId
import com.reals.backend.controller.dto.CreateProfileRequest
import com.reals.backend.controller.dto.PhotoResponse
import com.reals.backend.controller.dto.ProfileResponse
import com.reals.backend.controller.dto.ReorderProfilePhotosRequest
import com.reals.backend.controller.dto.UpdateMatchFiltersRequest
import com.reals.backend.controller.dto.UpdateProfileRequest
import com.reals.backend.service.ProfileService
import com.reals.backend.service.exception.DomainConflictException
import com.reals.backend.service.exception.DomainErrorCode
import com.reals.backend.service.exception.DomainNotFoundException
import com.reals.backend.service.photo.PhotoPlacement
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
@RequestMapping("/api/me/profile")
@Validated
class ProfileController(
    private val profileService: ProfileService
) {

    /**
     * Creates a DRAFT profile for a user.
     * A user can only have one profile.
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
            lookingForGenders = request.lookingForGenders,
            intention = request.intention,
            city = request.city,
            country = request.country,
            bio = request.bio,
            preferredMinAge = request.preferredMinAge,
            preferredMaxAge = request.preferredMaxAge,
            maxDistanceKm = request.maxDistanceKm
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
        val profile = findProfileForCurrentUserOrThrow(userId)

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
        val profile = findProfileForCurrentUserOrThrow(userId)

        val updated = profileService.updateProfile(
            profileId = profile.id,
            displayName = request.displayName,
            bio = request.bio,
            city = request.city,
            country = request.country
            country = request.country,
            intention = request.intention
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
        @CurrentUserAuth authContext: CurrentUserAuthContext
    ): ResponseEntity<ProfileResponse> {
        if (!authContext.emailVerified) {
            throw DomainConflictException(
                code = DomainErrorCode.EMAIL_NOT_VERIFIED,
                message = "Verificá tu email antes de activar el perfil."
            )
        }

        val profile = findProfileForCurrentUserOrThrow(authContext.userId)

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

    @PutMapping("/match-filters")
    fun updateMatchFilters(
        @CurrentUserId userId: UUID,
        @Valid
        @RequestBody request: UpdateMatchFiltersRequest
    ): ResponseEntity<ProfileResponse> {
        val profile = findProfileForCurrentUserOrThrow(userId)

        val updated = profileService.updateDynamicMatchFilters(
            profileId = profile.id,
            intention = request.intention,
            lookingForGenders = request.lookingForGenders,
            preferredMinAge = request.preferredMinAge,
            preferredMaxAge = request.preferredMaxAge,
            maxDistanceKm = request.maxDistanceKm
        )

        val photos = profileService.getPhotos(updated.id)

        return ResponseEntity.ok(
            ProfileResponse.from(
                profile = updated,
                photoCount = photos.size
            )
        )
    }

    @PostMapping("/identity-verification")
    fun verifyMyIdentity(
        @CurrentUserId userId: UUID
    ): ResponseEntity<ProfileResponse> {
        val profile = findProfileForCurrentUserOrThrow(userId)

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
     * Uploads a new profile photo file to object storage.
     *
     * Used when adding a new photo to an empty position.
     * Position is provided because the photo does not exist yet.
     */
    @PostMapping(
        "/photos",
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE]
    )
    fun uploadPhoto(
        @CurrentUserId userId: UUID,

        @RequestPart("file")
        file: MultipartFile,

        @RequestParam("position")
        @Min(1)
        position: Int
    ): ResponseEntity<PhotoResponse> {
        val profile = findProfileForCurrentUserOrThrow(userId)

        val photo = profileService.uploadPhoto(
            profileId = profile.id,
            position = position,
            contentType = file.contentType,
            bytes = file.bytes
        )

        return ResponseEntity.status(HttpStatus.CREATED).body(
            PhotoResponse.from(
                photo = photo,
                url = profileService.resolvePhotoReadUrlForResponse(photo)
            )
        )
    }

    /**
     * Returns all photos for the authenticated user's profile.
     *
     * URLs are resolved by the service, so the frontend never depends on
     * internal storage keys such as s3://bucket/key.
     */
    @GetMapping("/photos")
    fun getPhotos(
        @CurrentUserId userId: UUID
    ): ResponseEntity<List<PhotoResponse>> {
        val profile = findProfileForCurrentUserOrThrow(userId)

        return ResponseEntity.ok(
            profileService.getPhotoResponses(profileId = profile.id)
        )
    }

    @PutMapping("/photos/reorder")
    fun reorderPhotos(
        @CurrentUserId userId: UUID,
        @Valid
        @RequestBody request: ReorderProfilePhotosRequest
    ): ResponseEntity<List<PhotoResponse>> {
        val profile = findProfileForCurrentUserOrThrow(userId)

        val photos = profileService.reorderPhotos(
            profileId = profile.id,
            placements = request.placements.map {
                PhotoPlacement(
                    photoId = it.photoId,
                    position = it.position
                )
            }
        )

        return ResponseEntity.ok(
            photos.map {
                PhotoResponse.from(
                    photo = it,
                    url = profileService.resolvePhotoReadUrlForResponse(it)
                )
            }
        )
    }

    /**
     * Deletes an existing photo by photo id.
     *
     * photoId identifies the photo entity.
     * position should only be treated as an editable/order attribute.
     */
    @DeleteMapping("/photos/{photoId}")
    fun deletePhoto(
        @CurrentUserId userId: UUID,
        @PathVariable photoId: UUID
    ): ResponseEntity<ProfileResponse> {
        val profile = findProfileForCurrentUserOrThrow(userId)

        val updated = profileService.deletePhoto(
            profileId = profile.id,
            photoId = photoId
        )

        val photos = profileService.getPhotos(updated.id)

        return ResponseEntity.ok(
            ProfileResponse.from(
                profile = updated,
                photoCount = photos.size
            )
        )
    }

    /**
     * Replaces the binary file of an existing photo.
     *
     * photoId is used because this operation targets an existing photo entity.
     * The current position is preserved.
     */
    @PutMapping(
        "/photos/{photoId}/file",
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE]
    )
    fun replacePhotoFile(
        @CurrentUserId userId: UUID,

        @PathVariable photoId: UUID,

        @RequestPart("file")
        file: MultipartFile
    ): ResponseEntity<PhotoResponse> {
        val profile = findProfileForCurrentUserOrThrow(userId)

        val photo = profileService.replacePhoto(
            profileId = profile.id,
            photoId = photoId,
            contentType = file.contentType,
            bytes = file.bytes
        )

        return ResponseEntity.ok(
            PhotoResponse.from(
                photo = photo,
                url = profileService.resolvePhotoReadUrlForResponse(photo)
            )
        )
    }

    private fun findProfileForCurrentUserOrThrow(userId: UUID) =
        profileService.findByUserId(userId)
            ?: throw DomainNotFoundException(
                code = DomainErrorCode.PROFILE_NOT_FOUND,
                message = "Profile not found for current user"
            )
}
