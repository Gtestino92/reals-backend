package com.reals.backend.service.contacts

import com.reals.backend.config.*
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.*

class ContactPolicyTest {
    private val phones = ContactPhoneNormalizer()
    private val synthetic = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    private fun encoder(version: Int = 1) = ContactPseudonymizer(SharedContactsProperties(
        enabled=true, keyVersion=version, secret=synthetic, maxDistinct=2, maxRaw=3), phones)

    @Test fun `normalize only valid explicitly contextualized dial strings`() {
        assertEquals("+5491112345678", phones.normalize("011 15 1234-5678", "AR"))
        assertEquals("+5491112345678", phones.normalize("+54 9 11 1234-5678", "US"))
        assertEquals("+12025550100", phones.normalize("(202) 555-0100", "US"))
        for (invalid in listOf("123", "+12025550100 x3", "call me", "+9991234")) assertNull(phones.normalize(invalid, "AR"))
        assertNull(phones.normalize("2025550100", null))
    }

    @Test fun `digest equality depends on key version and normalized number`() {
        val numbers = ContactSelection(listOf("+12025550100", "202 555 0100"), "US")
        val set = encoder().identifiers(numbers)
        assertEquals(1, set.size)
        assertEquals(32, set.single().remaining())
        assertEquals(set, encoder().identifiers(numbers))
        assertNotEquals(set, encoder(2).identifiers(numbers))
        for (value in listOf("", "invalid", "YQ==")) assertFailsWith<IllegalArgumentException> { SharedContactsProperties(enabled=true, secret=value) }
        assertNull(SharedContactsProperties().key)
        for (selection in listOf(List(4) { "+12025550100" }, listOf("+12025550100","+12025550101","+12025550102"),listOf("9".repeat(65)))) {
            val e = assertFailsWith<ContactRequestError> { encoder().identifiers(ContactSelection(selection)) }
            assertFalse(e.message.orEmpty().contains(selection.first()))
        }
    }

    @Test fun `bounded monotonic weight and inclusive range edges`() {
        val policy = SocialRankingProperties()
        assertEquals(0.0, policy.weight(0))
        assertTrue((0..2000).map(policy::weight).zipWithNext().all { (a,b) -> a <= b && b <= kotlin.math.ln1p(0.30) })
        assertEquals(listOf(null,"ONE_TO_FIVE","ONE_TO_FIVE","SIX_TO_TEN","SIX_TO_TEN","MORE_THAN_TEN"),
            listOf(0,1,5,6,10,11).map { SharedContactRange.forCount(it)?.name })
    }
}
