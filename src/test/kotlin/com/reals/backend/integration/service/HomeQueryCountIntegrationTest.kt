package com.reals.backend.integration.service

import com.reals.backend.domain.Gender
import com.reals.backend.integration.BaseIT
import com.reals.backend.service.MeHomeService
import jakarta.persistence.EntityManager
import org.hibernate.SessionFactory
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.util.UUID

@TestPropertySource(
    properties = [
        "spring.jpa.properties.hibernate.generate_statistics=true"
    ]
)
class HomeQueryCountIntegrationTest : BaseIT() {

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var meHomeService: MeHomeService

    @Test
    fun `home query counts stay bounded as active first chat matches grow`() {
        val noInteractionUserId = createMeasuredUser("home-query-none")
        homeStatusService.getOrCreateStatus(noInteractionUserId)

        val oneMatchUserId = createUserWithActiveFirstChats("home-query-one", 1)
        homeStatusService.getOrCreateStatus(oneMatchUserId)

        val manyMatchesUserId = createUserWithActiveFirstChats("home-query-many", 3)
        homeStatusService.getOrCreateStatus(manyMatchesUserId)

        val fullNone = measurePreparedStatements("home full none") {
            meHomeService.getHome(noInteractionUserId)
        }
        val pendingNone = measurePreparedStatements("home pending none") {
            meHomeService.getPendingHomeState(noInteractionUserId)
        }
        val fullOne = measurePreparedStatements("home full one active first chat") {
            meHomeService.getHome(oneMatchUserId)
        }
        val fullMany = measurePreparedStatements("home full three active first chats") {
            meHomeService.getHome(manyMatchesUserId)
        }
        val pendingOne = measurePreparedStatements("home pending one active first chat") {
            meHomeService.getPendingHomeState(oneMatchUserId)
        }
        val pendingMany = measurePreparedStatements("home pending three active first chats") {
            meHomeService.getPendingHomeState(manyMatchesUserId)
        }

        println(
            "Home query-count baseline: " +
                "fullNone=$fullNone, pendingNone=$pendingNone, " +
                "fullOne=$fullOne, fullMany=$fullMany, " +
                "pendingOne=$pendingOne, pendingMany=$pendingMany"
        )

        assertTrue(
            fullMany <= fullOne + 2,
            "full Home should not add one decision query per match: one=$fullOne many=$fullMany"
        )
        assertTrue(
            pendingMany <= pendingOne + 2,
            "pending Home should not add one decision query per match: one=$pendingOne many=$pendingMany"
        )
        assertTrue(fullNone > 0)
        assertTrue(pendingNone > 0)
    }

    private fun measurePreparedStatements(
        label: String,
        operation: () -> Unit
    ): Long {
        entityManager.flush()
        entityManager.clear()

        val statistics = entityManager
            .entityManagerFactory
            .unwrap(SessionFactory::class.java)
            .statistics
        statistics.isStatisticsEnabled = true
        statistics.clear()

        operation()

        val count = statistics.prepareStatementCount
        println("$label prepared statements: $count")
        return count
    }

    private fun createUserWithActiveFirstChats(
        label: String,
        matchCount: Int
    ): UUID {
        val userId = createMeasuredUser("$label-anchor")
        repeat(matchCount) { index ->
            val partnerId = createMeasuredPartner("$label-partner-$index")
            val match = matchService.createMatch(userId, partnerId)
            chatService.startFirstChat(match.id)
        }
        return userId
    }

    private fun createMeasuredUser(label: String): UUID =
        createActiveProfile(
            email = "$label-${UUID.randomUUID()}@example.com",
            displayName = "Home Query $label",
            gender = Gender.FEMALE,
            lookingForGenders = setOf(Gender.MALE)
        )

    private fun createMeasuredPartner(label: String): UUID =
        createActiveProfile(
            email = "$label-${UUID.randomUUID()}@example.com",
            displayName = "Home Query $label",
            gender = Gender.MALE,
            lookingForGenders = setOf(Gender.FEMALE)
        )
}
