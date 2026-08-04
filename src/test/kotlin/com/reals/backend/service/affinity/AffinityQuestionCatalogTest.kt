package com.reals.backend.service.affinity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.assertFailsWith

class AffinityQuestionCatalogTest {
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `valid catalog loads`() {
        val catalog = baseCatalog()

        AffinityQuestionCatalogValidator.validate(catalog)

        assertEquals("2026.08.0", catalog.catalogVersion)
        assertEquals(36, catalog.activeQuestions.size)
    }

    @Test
    fun `duplicate category ids fail`() {
        val catalog = baseCatalog()
        val invalid =
            catalog.copy(
                categories = catalog.categories + catalog.categories.first().copy(displayOrder = 99)
            )

        assertFailsWith<IllegalArgumentException> {
            AffinityQuestionCatalogValidator.validate(invalid)
        }
    }

    @Test
    fun `duplicate question ids fail`() {
        val catalog = baseCatalog()
        val invalid =
            catalog.copy(
                questions = catalog.questions + catalog.questions.first().copy(categoryId = "MUSIC")
            )

        assertFailsWith<IllegalArgumentException> {
            AffinityQuestionCatalogValidator.validate(invalid)
        }
    }

    @Test
    fun `duplicate option ids fail`() {
        val catalog = baseCatalog()
        val question =
            catalog.questions.first().copy(
                options = catalog.questions.first().options +
                    catalog.questions.first().options.first().copy(displayOrder = 99)
            )
        val invalid = catalog.replaceQuestion(question)

        assertFailsWith<IllegalArgumentException> {
            AffinityQuestionCatalogValidator.validate(invalid)
        }
    }

    @Test
    fun `invalid category reference fails`() {
        val catalog = baseCatalog()
        val invalid =
            catalog.replaceQuestion(
                catalog.questions.first().copy(categoryId = "MISSING")
            )

        assertFailsWith<IllegalArgumentException> {
            AffinityQuestionCatalogValidator.validate(invalid)
        }
    }

    @Test
    fun `incomplete matrix fails`() {
        val catalog = baseCatalog()
        val question = catalog.questions.first { it.id == "SOCIAL_ENERGY_001" }
        val matrix = question.rankingPolicy.matrix!!.toMutableMap()
        matrix["QUIET_ONE_ON_ONE"] = matrix["QUIET_ONE_ON_ONE"]!!.minus("GROUP_ENERGY")
        val invalid =
            catalog.replaceQuestion(
                question.copy(
                    rankingPolicy = question.rankingPolicy.copy(matrix = matrix)
                )
            )

        assertFailsWith<IllegalArgumentException> {
            AffinityQuestionCatalogValidator.validate(invalid)
        }
    }

    @Test
    fun `asymmetric matrix fails`() {
        val catalog = baseCatalog()
        val question = catalog.questions.first { it.id == "SOCIAL_ENERGY_001" }
        val matrix = question.rankingPolicy.matrix!!
            .mapValues { it.value.toMutableMap() }
            .toMutableMap()
        matrix["QUIET_ONE_ON_ONE"]!!["GROUP_ENERGY"] = -0.1
        val invalid =
            catalog.replaceQuestion(
                question.copy(
                    rankingPolicy = question.rankingPolicy.copy(matrix = matrix)
                )
            )

        assertFailsWith<IllegalArgumentException> {
            AffinityQuestionCatalogValidator.validate(invalid)
        }
    }

    @Test
    fun `unsupported answer type fails during catalog parse`() {
        val json = """
            {
              "catalogVersion": "test",
              "categories": [
                { "id": "CAT", "title": "Categoría", "displayOrder": 1 }
              ],
              "questions": [
                {
                  "id": "Q",
                  "semanticVersion": 1,
                  "contentVersion": 1,
                  "status": "ACTIVE",
                  "categoryId": "CAT",
                  "primaryTopic": "TOPIC",
                  "topicTags": [],
                  "construct": "DOMAIN_ENGAGEMENT",
                  "answerType": "MULTI_SELECT",
                  "prompt": "¿Pregunta?",
                  "options": [
                    { "code": "A", "label": "A", "displayOrder": 1, "value": 0.0 },
                    { "code": "B", "label": "B", "displayOrder": 2, "value": 1.0 }
                  ],
                  "rankingPolicy": { "type": "SHARED_ENGAGEMENT" },
                  "conversationPolicy": { "type": "SHARED_AFFINITY_CONVERSATION" },
                  "sensitivity": "STANDARD",
                  "rankingEnabled": true,
                  "conversationEnabled": true
                }
              ]
            }
        """.trimIndent()

        assertFailsWith<Exception> {
            objectMapper.readValue(json.byteInputStream(Charsets.UTF_8), AffinityQuestionCatalog::class.java)
        }
    }

    @Test
    fun `invalid range fails`() {
        val catalog = baseCatalog()
        val question = catalog.questions.first { it.id == "CINEMA_IMPORTANCE_001" }
        val invalid =
            catalog.replaceQuestion(
                question.copy(
                    options = question.options.mapIndexed { index, option ->
                        if (index == 0) option.copy(value = 2.0) else option
                    }
                )
            )

        assertFailsWith<IllegalArgumentException> {
            AffinityQuestionCatalogValidator.validate(invalid)
        }
    }

    @Test
    fun `ranking enabled with none policy fails`() {
        val catalog = baseCatalog()
        val question = catalog.questions.first { it.id == "CINEMA_IMPORTANCE_001" }
        val invalid =
            catalog.replaceQuestion(
                question.copy(
                    rankingEnabled = true,
                    rankingPolicy = RankingComparisonPolicyConfig(type = RankingComparisonPolicyType.NONE)
                )
            )

        assertFailsWith<IllegalArgumentException> {
            AffinityQuestionCatalogValidator.validate(invalid)
        }
    }

    @Test
    fun `ranking disabled with non-none policy fails`() {
        val catalog = baseCatalog()
        val question = catalog.questions.first { it.id == "CINEMA_IMPORTANCE_001" }
        val invalid =
            catalog.replaceQuestion(
                question.copy(
                    rankingEnabled = false,
                    rankingPolicy = RankingComparisonPolicyConfig(type = RankingComparisonPolicyType.SHARED_ENGAGEMENT)
                )
            )

        assertFailsWith<IllegalArgumentException> {
            AffinityQuestionCatalogValidator.validate(invalid)
        }
    }

    @Test
    fun `conversation enabled with none policy fails`() {
        val catalog = baseCatalog()
        val question = catalog.questions.first { it.id == "CINEMA_IMPORTANCE_001" }
        val invalid =
            catalog.replaceQuestion(
                question.copy(
                    conversationEnabled = true,
                    conversationPolicy = ConversationComparisonPolicyConfig(type = ConversationComparisonPolicyType.NONE)
                )
            )

        assertFailsWith<IllegalArgumentException> {
            AffinityQuestionCatalogValidator.validate(invalid)
        }
    }

    @Test
    fun `conversation disabled with non-none policy fails`() {
        val catalog = baseCatalog()
        val question = catalog.questions.first { it.id == "CINEMA_IMPORTANCE_001" }
        val invalid =
            catalog.replaceQuestion(
                question.copy(
                    conversationEnabled = false,
                    conversationPolicy = ConversationComparisonPolicyConfig(
                        type = ConversationComparisonPolicyType.SHARED_AFFINITY_CONVERSATION
                    )
                )
            )

        assertFailsWith<IllegalArgumentException> {
            AffinityQuestionCatalogValidator.validate(invalid)
        }
    }

    @Test
    fun `extra ranking matrix row fails`() {
        val catalog = baseCatalog()
        val question = catalog.questions.first { it.id == "SOCIAL_ENERGY_001" }
        val matrix = question.rankingPolicy.matrix!!.toMutableMap()
        matrix["UNKNOWN"] = matrix["QUIET_ONE_ON_ONE"]!!
        val invalid =
            catalog.replaceQuestion(
                question.copy(
                    rankingPolicy = question.rankingPolicy.copy(matrix = matrix)
                )
            )

        assertFailsWith<IllegalArgumentException> {
            AffinityQuestionCatalogValidator.validate(invalid)
        }
    }

    @Test
    fun `extra ranking matrix column fails`() {
        val catalog = baseCatalog()
        val question = catalog.questions.first { it.id == "SOCIAL_ENERGY_001" }
        val matrix = question.rankingPolicy.matrix!!
            .mapValues { it.value.toMutableMap() }
            .toMutableMap()
        matrix["QUIET_ONE_ON_ONE"]!!["UNKNOWN"] = 0.0
        val invalid =
            catalog.replaceQuestion(
                question.copy(
                    rankingPolicy = question.rankingPolicy.copy(matrix = matrix)
                )
            )

        assertFailsWith<IllegalArgumentException> {
            AffinityQuestionCatalogValidator.validate(invalid)
        }
    }

    @Test
    fun `extra conversation matrix keys fail`() {
        val catalog = baseCatalog()
        val question = catalog.questions.first { it.id == "SOCIAL_ENERGY_001" }
        val conversationMatrix = requireNotNull(question.conversationPolicy.matrix)
        val extraRowMatrix = conversationMatrix.toMutableMap()
        extraRowMatrix["UNKNOWN"] = extraRowMatrix["QUIET_ONE_ON_ONE"]!!
        val extraColumnMatrix = conversationMatrix
            .mapValues { it.value.toMutableMap() }
            .toMutableMap()
        extraColumnMatrix["QUIET_ONE_ON_ONE"]!!["UNKNOWN"] =
            ConversationMatrixCell(potential = 0.0, kind = ConversationKind.NEUTRAL)

        assertFailsWith<IllegalArgumentException> {
            AffinityQuestionCatalogValidator.validate(
                catalog.replaceQuestion(
                    question.copy(
                        conversationPolicy = question.conversationPolicy.copy(matrix = extraRowMatrix)
                    )
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AffinityQuestionCatalogValidator.validate(
                catalog.replaceQuestion(
                    question.copy(
                        conversationPolicy = question.conversationPolicy.copy(matrix = extraColumnMatrix)
                    )
                )
            )
        }
    }

    @Test
    fun `deprecated definitions are structurally validated`() {
        val catalog = baseCatalog()
        val question = catalog.questions.first { it.status == AffinityQuestionStatus.DEPRECATED }
        val invalid =
            catalog.replaceQuestion(
                question.copy(
                    rankingEnabled = true,
                    rankingPolicy = RankingComparisonPolicyConfig(type = RankingComparisonPolicyType.NONE)
                )
            )

        assertFailsWith<IllegalArgumentException> {
            AffinityQuestionCatalogValidator.validate(invalid)
        }
    }

    @Test
    fun `initial catalog contains required categories`() {
        val categoryIds = baseCatalog().categories.map { it.id }

        assertEquals(
            listOf(
                "CINEMA_SERIES_AND_STORIES",
                "MUSIC",
                "ARTS_CULTURE_AND_IDEAS",
                "ACTIVITIES_SPORTS_AND_NATURE",
                "TRAVEL_AND_EXPLORATION",
                "FOOD_AND_EXPERIENCES",
                "PLANS_GAMES_AND_SOCIAL_LIFE",
                "VALUES_AND_SHARED_LIFE",
                "RELATIONSHIP_AND_COMMUNICATION"
            ),
            categoryIds
        )
    }

    @Test
    fun `sensitive public-life and belief questions do not request orientation or affiliation`() {
        val sensitiveQuestions =
            baseCatalog().activeQuestions.filter {
                it.primaryTopic in setOf("PUBLIC_LIFE_DISCUSSION", "BELIEFS_SALIENCE")
            }

        assertEquals(2, sensitiveQuestions.size)
        sensitiveQuestions.forEach { question ->
            assertEquals(AffinitySensitivity.SENSITIVE_LOW_RANKING, question.sensitivity)
            assertTrue(question.rankingPolicy.maxContribution <= 0.2)

            val normalizedPrompt = question.prompt.lowercase()
            listOf(
                "partido",
                "ideología",
                "afiliación",
                "denominación",
                "doctrina",
                "orientación política"
            ).forEach { prohibited ->
                assertTrue(
                    !normalizedPrompt.contains(prohibited),
                    "Question ${question.id} must not ask for $prohibited"
                )
            }
        }
    }

    @Test
    fun `calibrated catalog questions measure orientation not agreement importance`() {
        val catalog = baseCatalog()
        val ambition = catalog.questions.first { it.id == "AMBITION_BALANCE_001" }
        val money = catalog.questions.first { it.id == "MONEY_PLANS_STYLE_001" }
        val conflict = catalog.questions.first { it.id == "CONFLICT_REPAIR_001" }
        val tolerance = catalog.questions.first { it.id == "DIFFERENCE_TOLERANCE_001" }

        assertEquals(1, ambition.semanticVersion)
        assertEquals(AffinityConstruct.LIFESTYLE_ALIGNMENT, ambition.construct)
        assertEquals(AffinityAnswerType.ORDINAL_SCALE, ambition.answerType)
        assertEquals(RankingComparisonPolicyType.ORDINAL_ALIGNMENT, ambition.rankingPolicy.type)
        assertTrue(ambition.prompt.contains("metas, trabajo, tiempo personal y descanso"))
        assertFalse(ambition.prompt.lowercase().contains("compartir una mirada"))
        assertEquals("PERSONAL_TIME_FIRST", ambition.options.first().code)
        assertEquals("HIGH_GOAL_FOCUS", ambition.options.last().code)

        assertEquals(1, money.semanticVersion)
        assertEquals(AffinityAnswerType.ORDINAL_SCALE, money.answerType)
        assertEquals(RankingComparisonPolicyType.ORDINAL_ALIGNMENT, money.rankingPolicy.type)
        assertTrue(money.prompt.contains("gastos, disfrute y planes a futuro"))
        assertFalse(money.prompt.lowercase().contains("compatible"))
        assertEquals("PRESENT_ENJOYMENT", money.options.first().code)
        assertEquals("FUTURE_PREPARATION", money.options.last().code)

        assertEquals(1, conflict.semanticVersion)
        assertEquals(AffinityAnswerType.ORDINAL_SCALE, conflict.answerType)
        assertEquals(RankingComparisonPolicyType.ORDINAL_ALIGNMENT, conflict.rankingPolicy.type)
        assertTrue(conflict.prompt.contains("ritmo de conversación"))
        assertFalse(conflict.prompt.lowercase().contains("reparar"))
        assertEquals("NEED_LONG_PROCESSING", conflict.options.first().code)
        assertEquals("TALK_PROMPTLY", conflict.options.last().code)

        assertEquals(1, tolerance.semanticVersion)
        assertFalse(tolerance.rankingEnabled)
        assertEquals(RankingComparisonPolicyType.NONE, tolerance.rankingPolicy.type)
        assertTrue(tolerance.conversationEnabled)
    }

    private fun baseCatalog(): AffinityQuestionCatalog {
        val input =
            requireNotNull(
                javaClass.classLoader.getResourceAsStream("affinity-questions.es-AR.json")
            )
        return input.use {
            objectMapper.readValue(it, AffinityQuestionCatalog::class.java)
        }
    }

    private fun AffinityQuestionCatalog.replaceQuestion(question: AffinityQuestion): AffinityQuestionCatalog =
        copy(
            questions = questions.map {
                if (it.id == question.id) question else it
            }
        )
}
