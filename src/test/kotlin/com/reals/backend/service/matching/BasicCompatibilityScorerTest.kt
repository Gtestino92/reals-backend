package com.reals.backend.service.matching

import com.reals.backend.domain.Gender
import com.reals.backend.domain.Intention
import com.reals.backend.domain.Profile
import com.reals.backend.domain.ProfileStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class BasicCompatibilityScorerTest {

    private val scorer = BasicCompatibilityScorer(BasicCompatibilityEvaluator())

    @Test
    fun `compatible profiles score one for probabilistic ranking input`() {
        val female = profile(
            userId = UUID.randomUUID(),
            gender = Gender.FEMALE,
            lookingFor = Gender.MALE
        )
        val male = profile(
            userId = UUID.randomUUID(),
            gender = Gender.MALE,
            lookingFor = Gender.FEMALE
        )

        assertEquals(1.0, scorer.score(female, male))
    }

    @Test
    fun `incompatible profiles score zero`() {
        val female = profile(
            userId = UUID.randomUUID(),
            gender = Gender.FEMALE,
            lookingFor = Gender.FEMALE
        )
        val male = profile(
            userId = UUID.randomUUID(),
            gender = Gender.MALE,
            lookingFor = Gender.FEMALE
        )

        assertEquals(0.0, scorer.score(female, male))
    }

    private fun profile(
        userId: UUID,
        gender: Gender,
        lookingFor: Gender
    ): Profile =
        Profile(
            userId = userId,
            displayName = "User",
            birthDate = LocalDate.now().minusYears(30),
            gender = gender,
            lookingForGenders = mutableSetOf(lookingFor),
            intention = Intention.DATE,
            city = "Buenos Aires",
            countryCode = "AR",
            status = ProfileStatus.ACTIVE
        )
}
