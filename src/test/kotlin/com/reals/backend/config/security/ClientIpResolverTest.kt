package com.reals.backend.config.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class ClientIpResolverTest {
    private val resolver = ClientIpResolver("10.0.0.5, fd00::5")

    @Test
    fun `direct requests cannot select an address using forwarding headers`() {
        val request = request("10.0.0.6", "192.0.2.10")
        request.addHeader("X-Forwarded-For", "192.0.2.20")
        request.addHeader("Forwarded", "for=192.0.2.20")
        assertEquals("10.0.0.6", resolver.resolve(request))
        assertEquals("10.0.0.5", ClientIpResolver("").resolve(request("10.0.0.5", "192.0.2.10")))
    }

    @Test
    fun `trusted peers supply distinct canonical client IPs through a single real IP header`() {
        assertEquals("192.0.2.10", resolver.resolve(request("10.0.0.5", "192.0.2.10")))
        assertEquals("2001:db8::2", resolver.resolve(request("fd00:0:0:0:0:0:0:5", "2001:0db8::2")))
    }

    @Test
    fun `missing malformed or repeated real IP falls back to direct peer`() {
        for (headers in listOf(
            emptyList(), listOf("example.test"), listOf("192.0.2.1:8080"), listOf("fe80::1%eth0"),
            listOf("192.0.2.1, 192.0.2.2"), listOf("192.0.2.1", "192.0.2.2")
        )) {
            val request = request("10.0.0.5", *headers.toTypedArray())
            request.addHeader("X-Forwarded-For", "192.0.2.20")
            assertEquals("10.0.0.5", resolver.resolve(request))
        }
    }

    private fun request(peer: String, vararg realIp: String) = MockHttpServletRequest().apply {
        remoteAddr = peer
        realIp.forEach { addHeader("X-Real-IP", it) }
    }
}
