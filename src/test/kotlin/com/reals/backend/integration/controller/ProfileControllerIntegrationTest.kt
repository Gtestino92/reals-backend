package com.reals.backend.integration.controller

import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.PhotoModerationStatus
import com.reals.backend.domain.PhotoStorageProvider
import com.reals.backend.domain.PhotoValidationStatus
import com.reals.backend.domain.ProfilePhoto
import com.reals.backend.domain.ProfileStatus
import com.reals.backend.integration.ControllerIT
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

class ProfileControllerIntegrationTest : ControllerIT() {

    @Test
    fun `get me resolves authenticated user id`() {
        val user = userService.createUser("me-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            get("/api/me")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id", equalTo(user.id.toString())))
            .andExpect(jsonPath("$.email", equalTo(user.email)))
    }

    @Test
    fun `create profile uses authenticated user id`() {
        val user = userService.createUser("profile-${UUID.randomUUID()}@example.com")
        val body = mapOf(
            "displayName" to "Controller Profile",
            "birthDate" to LocalDate.of(1995, 1, 1).toString(),
            "gender" to Gender.FEMALE.name,
            "lookingForGenders" to listOf(Gender.MALE.name),
            "intention" to Intention.DATE.name,
            "city" to "Buenos Aires",
            "countryCode" to "AR",
            "bio" to "Created through MockMvc",
            "preferredMinAge" to 30,
            "preferredMaxAge" to 40,
            "maxDistanceKm" to 75
        )

        mockMvc.perform(
            post("/api/me/profile")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(jsonBody(body))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.userId", equalTo(user.id.toString())))
            .andExpect(jsonPath("$.displayName", equalTo("Controller Profile")))
            .andExpect(jsonPath("$.preferredMinAge", equalTo(30)))
            .andExpect(jsonPath("$.preferredMaxAge", equalTo(40)))
            .andExpect(jsonPath("$.maxDistanceKm", equalTo(75)))
            .andExpect(jsonPath("$.city", equalTo("Buenos Aires")))
            .andExpect(jsonPath("$.countryCode", equalTo("AR")))
            .andExpect(jsonPath("$.country").doesNotExist())
            .andExpect(jsonPath("$.lookingForGenders", containsInAnyOrder("MALE")))
            .andExpect(jsonPath("$.authenticityVerified", equalTo(false)))
            .andExpect(jsonPath("$.authenticityVerificationStatus", equalTo("NOT_STARTED")))
            .andExpect(jsonPath("$.status", equalTo("DRAFT")))
    }

    @Test
    fun `create profile normalizes country code before persistence`() {
        val user = userService.createUser("profile-country-normalized-${UUID.randomUUID()}@example.com")
        val body = validCreateProfileBody().toMutableMap()
        body["countryCode"] = " ar "

        mockMvc.perform(
            post("/api/me/profile")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(jsonBody(body))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.countryCode", equalTo("AR")))
            .andExpect(jsonPath("$.country").doesNotExist())

        val profile = profileService.findByUserId(user.id) ?: error("Expected profile")
        assertEquals("AR", profile.countryCode)
    }

    @Test
    fun `create profile rejects unknown country code with stable error code`() {
        val user = userService.createUser("profile-country-invalid-${UUID.randomUUID()}@example.com")
        val body = validCreateProfileBody().toMutableMap()
        body["countryCode"] = "ZZ"

        mockMvc.perform(
            post("/api/me/profile")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(jsonBody(body))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("INVALID_PROFILE_COUNTRY")))
    }

    @Test
    fun `update profile normalizes valid country code and rejects invalid values`() {
        val user = userService.createUser("profile-country-update-${UUID.randomUUID()}@example.com")
        val profile = profileService.createProfile(
            userId = user.id,
            displayName = "Country Update",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE),
            intention = Intention.DATE,
            city = "Buenos Aires",
            countryCode = "AR",
            preferredMinAge = 18,
            preferredMaxAge = 99,
            maxDistanceKm = 50
        )

        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/me/profile")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(jsonBody(mapOf("countryCode" to " uy ")))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.countryCode", equalTo("UY")))

        assertEquals("UY", profileService.findByIdOrThrow(profile.id).countryCode)

        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/me/profile")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(jsonBody(mapOf("countryCode" to "Argentina")))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("INVALID_PROFILE_COUNTRY")))
    }

    @Test
    fun `update profile null country code preserves existing value`() {
        val user = userService.createUser("profile-country-null-${UUID.randomUUID()}@example.com")
        val profile = profileService.createProfile(
            userId = user.id,
            displayName = "Country Preserve",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE),
            intention = Intention.DATE,
            city = "Buenos Aires",
            countryCode = "AR",
            preferredMinAge = 18,
            preferredMaxAge = 99,
            maxDistanceKm = 50
        )

        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/me/profile")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(jsonBody(mapOf("city" to "Cordoba", "countryCode" to null)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.city", equalTo("Cordoba")))
            .andExpect(jsonPath("$.countryCode", equalTo("AR")))
            .andExpect(jsonPath("$.country").doesNotExist())

        assertEquals("AR", profileService.findByIdOrThrow(profile.id).countryCode)
    }

    @Test
    fun `create profile accepts multiple target genders`() {
        val user = userService.createUser("profile-multi-genders-${UUID.randomUUID()}@example.com")
        val body = validCreateProfileBody(
            displayName = "Multi Gender Profile",
            lookingForGenders = listOf(Gender.FEMALE.name, Gender.NON_BINARY.name)
        )

        mockMvc.perform(
            post("/api/me/profile")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(jsonBody(body))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.lookingForGenders", containsInAnyOrder("FEMALE", "NON_BINARY")))
    }

    @Test
    fun `create profile accepts all target genders`() {
        val user = userService.createUser("profile-all-genders-${UUID.randomUUID()}@example.com")
        val body = validCreateProfileBody(
            displayName = "All Gender Profile",
            lookingForGenders = Gender.entries.map { it.name }
        )

        mockMvc.perform(
            post("/api/me/profile")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(jsonBody(body))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.lookingForGenders", containsInAnyOrder("MALE", "FEMALE", "NON_BINARY", "OTHER")))
    }

    @Test
    fun `create profile rejects empty target gender set`() {
        val user = userService.createUser("profile-empty-genders-${UUID.randomUUID()}@example.com")
        val body = validCreateProfileBody(
            displayName = "Empty Gender Profile",
            lookingForGenders = emptyList()
        )

        mockMvc.perform(
            post("/api/me/profile")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(jsonBody(body))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("VALIDATION_ERROR")))
    }

    @Test
    fun `update profile target genders null leaves existing set unchanged`() {
        val user = userService.createUser("profile-null-genders-${UUID.randomUUID()}@example.com")
        profileService.createProfile(
            userId = user.id,
            displayName = "Null Genders",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE, Gender.NON_BINARY),
            intention = Intention.DATE,
            city = "Buenos Aires",
            countryCode = "AR",
            preferredMinAge = 18,
            preferredMaxAge = 99,
            maxDistanceKm = 50
        )

        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/me/profile")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(jsonBody(mapOf("city" to "Cordoba")))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.city", equalTo("Cordoba")))
            .andExpect(jsonPath("$.lookingForGenders", containsInAnyOrder("FEMALE", "NON_BINARY")))
    }

    @Test
    fun `update match filters target genders replaces existing set`() {
        val user = userService.createUser("profile-replace-genders-${UUID.randomUUID()}@example.com")
        profileService.createProfile(
            userId = user.id,
            displayName = "Replace Genders",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE, Gender.NON_BINARY),
            intention = Intention.DATE,
            city = "Buenos Aires",
            countryCode = "AR",
            preferredMinAge = 18,
            preferredMaxAge = 99,
            maxDistanceKm = 50
        )

        mockMvc.perform(
            put("/api/me/profile/match-filters")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(
                    jsonBody(
                        mapOf(
                            "intention" to Intention.FRIENDSHIP.name,
                            "lookingForGenders" to listOf(Gender.OTHER.name),
                            "preferredMinAge" to 21,
                            "preferredMaxAge" to 45,
                            "maxDistanceKm" to 75
                        )
                    )
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.intention", equalTo("FRIENDSHIP")))
            .andExpect(jsonPath("$.lookingForGenders", containsInAnyOrder("OTHER")))
            .andExpect(jsonPath("$.preferredMinAge", equalTo(21)))
            .andExpect(jsonPath("$.preferredMaxAge", equalTo(45)))
            .andExpect(jsonPath("$.maxDistanceKm", equalTo(75)))
    }

    @Test
    fun `update match filters rejects empty target gender set`() {
        val user = userService.createUser("profile-update-empty-genders-${UUID.randomUUID()}@example.com")
        profileService.createProfile(
            userId = user.id,
            displayName = "Reject Empty Genders",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE),
            intention = Intention.DATE,
            city = "Buenos Aires",
            countryCode = "AR",
            preferredMinAge = 18,
            preferredMaxAge = 99,
            maxDistanceKm = 50
        )

        mockMvc.perform(
            put("/api/me/profile/match-filters")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(
                    jsonBody(
                        mapOf(
                            "intention" to Intention.DATE.name,
                            "lookingForGenders" to emptyList<String>(),
                            "preferredMinAge" to 18,
                            "preferredMaxAge" to 99,
                            "maxDistanceKm" to 50
                        )
                    )
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("VALIDATION_ERROR")))
    }

    @Test
    fun `noop profile authenticity verification marks profile verified`() {
        val user = userService.createUser("identity-noop-${UUID.randomUUID()}@example.com")
        profileService.createProfile(
            userId = user.id,
            displayName = "Authenticity Noop",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE),
            intention = Intention.DATE,
            city = "Buenos Aires",
            countryCode = "AR",
            preferredMinAge = 18,
            preferredMaxAge = 99,
            maxDistanceKm = 50
        )

        mockMvc.perform(
            post("/api/me/profile/authenticity-verification")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.authenticityVerified", equalTo(true)))
            .andExpect(jsonPath("$.authenticityVerificationStatus", equalTo("VERIFIED")))

        val profile = profileService.findByUserId(user.id) ?: error("Expected profile")
        org.junit.jupiter.api.Assertions.assertEquals(true, profile.authenticityVerified)
        org.junit.jupiter.api.Assertions.assertEquals(
            com.reals.backend.domain.ProfileAuthenticityVerificationStatus.VERIFIED,
            profile.authenticityVerificationStatus
        )
    }

    @Test
    fun `create profile rejects markup in text fields`() {
        val user = userService.createUser("profile-markup-${UUID.randomUUID()}@example.com")
        val body = mapOf(
            "displayName" to "<script>alert(1)</script>",
            "birthDate" to LocalDate.of(1995, 1, 1).toString(),
            "gender" to Gender.FEMALE.name,
            "lookingForGenders" to listOf(Gender.MALE.name),
            "intention" to Intention.DATE.name,
            "city" to "Buenos Aires",
            "countryCode" to "AR",
            "bio" to "Plain bio",
            "preferredMinAge" to 30,
            "preferredMaxAge" to 40,
            "maxDistanceKm" to 75
        )

        mockMvc.perform(
            post("/api/me/profile")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(jsonBody(body))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("VALIDATION_ERROR")))
    }

    @Test
    fun `create profile accepts multiline bio and rejects newline in single line fields`() {
        val acceptedUser = userService.createUser("profile-multiline-bio-${UUID.randomUUID()}@example.com")
        mockMvc.perform(
            post("/api/me/profile")
                .with(authenticatedAs(acceptedUser.id))
                .contentType(jsonContentType)
                .content(jsonBody(validCreateProfileBody(bio = "Line one\nLine two\rLine three")))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.bio", equalTo("Line one\nLine two\rLine three")))

        listOf("displayName", "city", "countryCode").forEach { field ->
            val user = userService.createUser("profile-newline-$field-${UUID.randomUUID()}@example.com")
            val body = validCreateProfileBody().toMutableMap()
            body[field] = "Bad\n$field"

            mockMvc.perform(
                post("/api/me/profile")
                    .with(authenticatedAs(user.id))
                    .contentType(jsonContentType)
                    .content(jsonBody(body))
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code", equalTo("VALIDATION_ERROR")))
        }
    }

    @Test
    fun `update profile accepts multiline bio and rejects newline in single line fields`() {
        val acceptedUser = userService.createUser("profile-update-multiline-bio-${UUID.randomUUID()}@example.com")
        profileService.createProfile(
            userId = acceptedUser.id,
            displayName = "Update Bio",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE),
            intention = Intention.DATE,
            city = "Buenos Aires",
            countryCode = "AR",
            preferredMinAge = 18,
            preferredMaxAge = 99,
            maxDistanceKm = 50
        )

        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/me/profile")
                .with(authenticatedAs(acceptedUser.id))
                .contentType(jsonContentType)
                .content(jsonBody(mapOf("bio" to "Line one\nLine two\rLine three")))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.bio", equalTo("Line one\nLine two\rLine three")))

        listOf("displayName", "city", "countryCode").forEach { field ->
            val user = userService.createUser("profile-update-newline-$field-${UUID.randomUUID()}@example.com")
            profileService.createProfile(
                userId = user.id,
                displayName = "Update Single Line",
                birthDate = LocalDate.of(1995, 1, 1),
                gender = Gender.FEMALE,
                lookingForGenders = setOf(Gender.MALE),
                intention = Intention.DATE,
                city = "Buenos Aires",
                countryCode = "AR",
                preferredMinAge = 18,
                preferredMaxAge = 99,
                maxDistanceKm = 50
            )

            mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/me/profile")
                    .with(authenticatedAs(user.id))
                    .contentType(jsonContentType)
                    .content(jsonBody(mapOf(field to "Bad\n$field")))
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code", equalTo("VALIDATION_ERROR")))
        }
    }

    @Test
    fun `get missing profile returns stable error code`() {
        val user = userService.createUser("missing-profile-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            get("/api/me/profile")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code", equalTo("PROFILE_NOT_FOUND")))
    }

    @Test
    fun `json photo creation endpoint is not supported`() {
        val user = userService.createUser("missing-profile-action-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            post("/api/me/profile/photos")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content("""{"url":"https://example.com/photo.jpg","position":1}""")
        )
            .andExpect(status().isUnsupportedMediaType)
    }

    @Test
    fun `update match filters replaces required dynamic filters`() {
        val user = userService.createUser("filters-${UUID.randomUUID()}@example.com")
        profileService.createProfile(
            userId = user.id,
            displayName = "Filter Profile",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE),
            intention = Intention.DATE,
            city = "Buenos Aires",
            countryCode = "AR",
            preferredMinAge = 25,
            preferredMaxAge = 35,
            maxDistanceKm = 100
        )

        mockMvc.perform(
            put("/api/me/profile/match-filters")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(
                    """
                    {
                      "intention": "FRIENDSHIP",
                      "lookingForGenders": ["MALE", "FEMALE"],
                      "preferredMinAge": 30,
                      "preferredMaxAge": 38,
                      "maxDistanceKm": 25
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.intention", equalTo("FRIENDSHIP")))
            .andExpect(jsonPath("$.lookingForGenders", containsInAnyOrder("MALE", "FEMALE")))
            .andExpect(jsonPath("$.preferredMinAge", equalTo(30)))
            .andExpect(jsonPath("$.preferredMaxAge", equalTo(38)))
            .andExpect(jsonPath("$.maxDistanceKm", equalTo(25)))
    }

    @Test
    fun `underage profile returns bad request`() {
        val user = userService.createUser("underage-${UUID.randomUUID()}@example.com")
        val body = mapOf(
            "displayName" to "Young Profile",
            "birthDate" to LocalDate.now().minusYears(17).toString(),
            "gender" to Gender.FEMALE.name,
            "lookingForGenders" to listOf(Gender.MALE.name),
            "intention" to Intention.DATE.name,
            "city" to "Buenos Aires",
            "countryCode" to "AR",
            "preferredMinAge" to 18,
            "preferredMaxAge" to 99,
            "maxDistanceKm" to 50
        )

        mockMvc.perform(
            post("/api/me/profile")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(jsonBody(body))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error", equalTo("Bad Request")))
            .andExpect(jsonPath("$.code", equalTo("INVALID_PROFILE_BIRTH_DATE")))
    }

    @Test
    fun `create duplicate profile returns stable error code`() {
        val user = userService.createUser("duplicate-profile-${UUID.randomUUID()}@example.com")
        val body = mapOf(
            "displayName" to "Duplicate Profile",
            "birthDate" to LocalDate.of(1995, 1, 1).toString(),
            "gender" to Gender.FEMALE.name,
            "lookingForGenders" to listOf(Gender.MALE.name),
            "intention" to Intention.DATE.name,
            "city" to "Buenos Aires",
            "countryCode" to "AR",
            "preferredMinAge" to 18,
            "preferredMaxAge" to 99,
            "maxDistanceKm" to 50
        )

        profileService.createProfile(
            userId = user.id,
            displayName = "Existing Profile",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE),
            intention = Intention.DATE,
            city = "Buenos Aires",
            countryCode = "AR",
            preferredMinAge = 18,
            preferredMaxAge = 99,
            maxDistanceKm = 50
        )

        mockMvc.perform(
            post("/api/me/profile")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(jsonBody(body))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("PROFILE_ALREADY_EXISTS")))
    }

    @Test
    fun `activate profile without required photos returns stable error code`() {
        val user = userService.createUser("activate-missing-photos-${UUID.randomUUID()}@example.com")
        profileService.createProfile(
            userId = user.id,
            displayName = "Missing Photos",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE),
            intention = Intention.DATE,
            city = "Buenos Aires",
            countryCode = "AR",
            preferredMinAge = 18,
            preferredMaxAge = 99,
            maxDistanceKm = 50
        )

        mockMvc.perform(
            post("/api/me/profile/activation")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("PROFILE_PHOTOS_REQUIRED")))
    }

    @Test
    fun `activate profile fails when email is not verified`() {
        val userId = createActivationReadyDraftProfile()

        mockMvc.perform(
            post("/api/me/profile/activation")
                .with(
                    authenticatedWithContext(
                        userId = userId,
                        firebaseUid = "firebase-$userId",
                        email = "unverified-$userId@example.com",
                        emailVerified = false
                    )
                )
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code", equalTo("EMAIL_NOT_VERIFIED")))
            .andExpect(jsonPath("$.message", equalTo("Verificá tu email antes de activar el perfil.")))

        val profile = profileService.findByUserId(userId) ?: error("Expected profile")
        assertEquals(ProfileStatus.DRAFT, profile.status)
    }

    @Test
    fun `activate profile succeeds when email is verified`() {
        val userId = createActivationReadyDraftProfile()

        mockMvc.perform(
            post("/api/me/profile/activation")
                .with(
                    authenticatedWithContext(
                        userId = userId,
                        firebaseUid = "firebase-$userId",
                        email = "verified-$userId@example.com",
                        emailVerified = true
                    )
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status", equalTo("ACTIVE")))

        val profile = profileService.findByUserId(userId) ?: error("Expected profile")
        assertEquals(ProfileStatus.ACTIVE, profile.status)
    }

    @Test
    fun `update match filters rejects inverted age range with stable error code`() {
        val user = userService.createUser("filters-invalid-range-${UUID.randomUUID()}@example.com")
        profileService.createProfile(
            userId = user.id,
            displayName = "Invalid Filters",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE),
            intention = Intention.DATE,
            city = "Buenos Aires",
            countryCode = "AR",
            preferredMinAge = 18,
            preferredMaxAge = 99,
            maxDistanceKm = 50
        )

        mockMvc.perform(
            put("/api/me/profile/match-filters")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(
                    """
                    {
                      "intention": "DATE",
                      "lookingForGenders": ["MALE"],
                      "preferredMinAge": 40,
                      "preferredMaxAge": 30,
                      "maxDistanceKm": 50
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code", equalTo("INVALID_MATCH_FILTERS")))
    }

    @Test
    fun `json photo replacement by position endpoint is not found`() {
        val user = userService.createUser("photo-position-${UUID.randomUUID()}@example.com")

        mockMvc.perform(
            put("/api/me/profile/photos/position/1")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content("""{"url":"https://example.com/photo.jpg"}""")
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `reorder photos returns photo responses ordered by position`() {
        val user = userService.createUser("reorder-controller-${UUID.randomUUID()}@example.com")
        val profile = profileService.createProfile(
            userId = user.id,
            displayName = "Reorder Controller",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE),
            intention = Intention.DATE,
            city = "Buenos Aires",
            countryCode = "AR",
            preferredMinAge = 18,
            preferredMaxAge = 99,
            maxDistanceKm = 50
        )
        val first = profilePhotoRepository.save(
            ProfilePhoto(
                profileId = profile.id,
                storageProvider = PhotoStorageProvider.S3,
                storageBucket = "reals-profile-photos-test",
                storageKey = "users/${user.id}/profile-photos/first.jpg",
                position = 1,
                isPersonPhoto = true,
                isFullBody = false,
                validationStatus = PhotoValidationStatus.VALIDATED,
                moderationStatus = PhotoModerationStatus.APPROVED
            )
        )
        val second = profilePhotoRepository.save(
            ProfilePhoto(
                profileId = profile.id,
                storageProvider = PhotoStorageProvider.S3,
                storageBucket = "reals-profile-photos-test",
                storageKey = "users/${user.id}/profile-photos/second.jpg",
                position = 2,
                isPersonPhoto = false,
                isFullBody = true,
                validationStatus = PhotoValidationStatus.PENDING,
                moderationStatus = PhotoModerationStatus.NEEDS_REVIEW
            )
        )

        mockMvc.perform(
            put("/api/me/profile/photos/reorder")
                .with(authenticatedAs(user.id))
                .contentType(jsonContentType)
                .content(
                    jsonBody(
                        mapOf(
                            "placements" to listOf(
                                mapOf("photoId" to first.id, "position" to 4),
                                mapOf("photoId" to second.id, "position" to 1)
                            )
                        )
                    )
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id", equalTo(second.id.toString())))
            .andExpect(jsonPath("$[0].position", equalTo(1)))
            .andExpect(jsonPath("$[0].isPersonPhoto", equalTo(false)))
            .andExpect(jsonPath("$[0].isFullBody", equalTo(true)))
            .andExpect(jsonPath("$[0].validationStatus", equalTo("PENDING")))
            .andExpect(jsonPath("$[0].moderationStatus", equalTo("NEEDS_REVIEW")))
            .andExpect(jsonPath("$[1].id", equalTo(first.id.toString())))
            .andExpect(jsonPath("$[1].position", equalTo(4)))
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `delete missing photo returns stable error code`() {
        val user = userService.createUser("missing-photo-${UUID.randomUUID()}@example.com")
        profileService.createProfile(
            userId = user.id,
            displayName = "Missing Photo",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE),
            intention = Intention.DATE,
            city = "Buenos Aires",
            countryCode = "AR",
            preferredMinAge = 18,
            preferredMaxAge = 99,
            maxDistanceKm = 50
        )

        mockMvc.perform(
            delete("/api/me/profile/photos/${UUID.randomUUID()}")
                .with(authenticatedAs(user.id))
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code", equalTo("PROFILE_PHOTO_NOT_FOUND")))
    }

    private fun createActivationReadyDraftProfile(): UUID {
        val user = userService.createUser("activation-ready-${UUID.randomUUID()}@example.com")
        val profile = profileService.createProfile(
            userId = user.id,
            displayName = "Activation Ready",
            birthDate = LocalDate.of(1995, 1, 1),
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE),
            intention = Intention.DATE,
            city = "Buenos Aires",
            countryCode = "AR",
            preferredMinAge = 18,
            preferredMaxAge = 99,
            maxDistanceKm = 50
        )

        repeat(4) { index ->
            profilePhotoRepository.save(
                ProfilePhoto(
                    profileId = profile.id,
                    storageProvider = PhotoStorageProvider.S3,
                    storageBucket = "reals-profile-photos-test",
                    storageKey = "users/${user.id}/profile-photos/${profile.id}-${index + 1}.jpg",
                    position = index + 1,
                    isPersonPhoto = index == 0,
                    isFullBody = index == 0,
                    validationStatus = PhotoValidationStatus.VALIDATED,
                    moderationStatus = PhotoModerationStatus.APPROVED
                )
            )
        }

        return user.id
    }

    private fun validCreateProfileBody(
        displayName: String = "Controller Profile",
        lookingForGenders: List<String> = listOf(Gender.MALE.name),
        bio: String? = "Plain bio"
    ): Map<String, Any?> =
        mapOf(
            "displayName" to displayName,
            "birthDate" to LocalDate.of(1995, 1, 1).toString(),
            "gender" to Gender.FEMALE.name,
            "lookingForGenders" to lookingForGenders,
            "intention" to Intention.DATE.name,
            "city" to "Buenos Aires",
            "countryCode" to "AR",
            "bio" to bio,
            "preferredMinAge" to 18,
            "preferredMaxAge" to 99,
            "maxDistanceKm" to 50
        )
}
