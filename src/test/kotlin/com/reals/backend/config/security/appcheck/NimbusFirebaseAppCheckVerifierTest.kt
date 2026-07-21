package com.reals.backend.config.security.appcheck

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NimbusFirebaseAppCheckVerifierTest {

    private val projectNumber = "123456789"
    private val allowedAppId = "1:123456789:android:allowed"
    private val keyPair = rsaKeyPair()
    private val properties = FirebaseAppCheckProperties(
        mode = FirebaseAppCheckMode.ENFORCED,
        projectNumber = projectNumber,
        allowedAppIds = listOf(allowedAppId)
    )

    @Test
    fun `valid token returns app id`() {
        val result = verifier().verify(token())

        assertEquals(
            FirebaseAppCheckVerificationResult.Valid(allowedAppId),
            result
        )
    }

    @Test
    fun `wrong signature is invalid`() {
        val result = verifier().verify(token(signingKey = rsaKeyPair()))

        assertEquals(FirebaseAppCheckVerificationResult.Invalid, result)
    }

    @Test
    fun `non RS256 algorithm is invalid`() {
        val result = verifier().verify(token(algorithm = JWSAlgorithm.RS512))

        assertEquals(FirebaseAppCheckVerificationResult.Invalid, result)
    }

    @Test
    fun `missing typ is invalid`() {
        val result = verifier().verify(token(type = null))

        assertEquals(FirebaseAppCheckVerificationResult.Invalid, result)
    }

    @Test
    fun `wrong typ is invalid`() {
        val result = verifier().verify(token(type = JOSEObjectType("JOSE")))

        assertEquals(FirebaseAppCheckVerificationResult.Invalid, result)
    }

    @Test
    fun `wrong issuer is invalid`() {
        val result = verifier().verify(token(issuer = "https://firebaseappcheck.googleapis.com/wrong"))

        assertEquals(FirebaseAppCheckVerificationResult.Invalid, result)
    }

    @Test
    fun `expired token is invalid`() {
        val result = verifier().verify(token(expiresAt = Instant.now().minusSeconds(60)))

        assertEquals(FirebaseAppCheckVerificationResult.Invalid, result)
    }

    @Test
    fun `wrong audience is invalid`() {
        val result = verifier().verify(token(audience = "projects/wrong"))

        assertEquals(FirebaseAppCheckVerificationResult.Invalid, result)
    }

    @Test
    fun `blank subject is invalid`() {
        val result = verifier().verify(token(subject = " "))

        assertEquals(FirebaseAppCheckVerificationResult.Invalid, result)
    }

    @Test
    fun `disallowed app id is invalid`() {
        val result = verifier().verify(token(subject = "1:123456789:android:other"))

        assertEquals(FirebaseAppCheckVerificationResult.Invalid, result)
    }

    @Test
    fun `allowed app id is valid`() {
        val result = verifier(
            properties.copy(allowedAppIds = listOf("other", allowedAppId))
        ).verify(token())

        assertEquals(
            FirebaseAppCheckVerificationResult.Valid(allowedAppId),
            result
        )
    }

    @Test
    fun `decoder unavailable is unavailable`() {
        val result = NimbusFirebaseAppCheckVerifier(
            decoderFactory = FirebaseAppCheckJwtDecoderFactory {
                throw IllegalStateException("jwks unavailable")
            },
            properties = properties
        ).verify("token")

        assertIs<FirebaseAppCheckVerificationResult.Unavailable>(result)
    }

    private fun verifier(
        verifierProperties: FirebaseAppCheckProperties = properties
    ): NimbusFirebaseAppCheckVerifier {
        val decoder = NimbusJwtDecoder
            .withPublicKey(keyPair.public as RSAPublicKey)
            .signatureAlgorithm(SignatureAlgorithm.RS256)
            .build()

        return NimbusFirebaseAppCheckVerifier(
            decoderFactory = FirebaseAppCheckJwtDecoderFactory { decoder },
            properties = verifierProperties
        )
    }

    private fun token(
        signingKey: KeyPair = keyPair,
        algorithm: JWSAlgorithm = JWSAlgorithm.RS256,
        type: JOSEObjectType? = JOSEObjectType.JWT,
        issuer: String = "https://firebaseappcheck.googleapis.com/$projectNumber",
        audience: String = "projects/$projectNumber",
        subject: String = allowedAppId,
        expiresAt: Instant = Instant.now().plusSeconds(300)
    ): String {
        val headerBuilder = JWSHeader.Builder(algorithm)
        if (type != null) {
            headerBuilder.type(type)
        }

        val jwt = SignedJWT(
            headerBuilder.build(),
            JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject(subject)
                .expirationTime(Date.from(expiresAt))
                .issueTime(Date.from(Instant.now()))
                .build()
        )

        jwt.sign(RSASSASigner(signingKey.private))
        return jwt.serialize()
    }

    private fun rsaKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        return generator.generateKeyPair()
    }
}
