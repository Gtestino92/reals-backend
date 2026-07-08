package com.reals.backend.integration.service

import com.reals.backend.domain.AuditEventType
import com.reals.backend.domain.LegalDocumentAction
import com.reals.backend.domain.LegalDocumentType
import com.reals.backend.repository.AuditEventRepository
import com.reals.backend.repository.UserLegalDocumentActionRepository
import com.reals.backend.service.LegalDocumentService
import com.reals.backend.service.UserService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@ActiveProfiles("test")
class LegalDocumentConcurrencyIntegrationTest {

    @Autowired
    private lateinit var legalDocumentService: LegalDocumentService

    @Autowired
    private lateinit var userService: UserService

    @Autowired
    private lateinit var legalDocumentActionRepository: UserLegalDocumentActionRepository

    @Autowired
    private lateinit var auditEventRepository: AuditEventRepository

    @Test
    fun `concurrent identical legal document actions create one row and one audit event`() {
        val user = userService.createUser("legal-concurrency-${UUID.randomUUID()}@example.com")
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)

        try {
            val futures = List(2) {
                executor.submit<LegalDocumentService.RecordActionResult> {
                    ready.countDown()
                    assertTrue(start.await(5, TimeUnit.SECONDS))

                    legalDocumentService.recordAction(
                        userId = user.id,
                        documentType = LegalDocumentType.TERMS_OF_USE,
                        documentVersion = "2026-07-01-test",
                        action = LegalDocumentAction.ACCEPTED
                    )
                }
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()

            val results = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, results.count { it.created })
            assertEquals(1, results.count { !it.created })
            assertEquals(1, results.map { it.action.id }.toSet().size)

            val storedActions = legalDocumentActionRepository.findByUserId(user.id)
                .filter {
                    it.documentType == LegalDocumentType.TERMS_OF_USE &&
                        it.documentVersion == "2026-07-01-test"
                }
            assertEquals(1, storedActions.size)
            assertEquals(storedActions.single().id, results.first().action.id)

            val auditEvents = auditEventRepository.findAll()
                .filter {
                    it.eventType == AuditEventType.LEGAL_DOCUMENT_ACTION_RECORDED &&
                        it.aggregateId == user.id &&
                        it.metadataJson?.contains("TERMS_OF_USE") == true &&
                        it.metadataJson?.contains("2026-07-01-test") == true &&
                        it.metadataJson?.contains("ACCEPTED") == true
                }
            assertEquals(1, auditEvents.size)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
