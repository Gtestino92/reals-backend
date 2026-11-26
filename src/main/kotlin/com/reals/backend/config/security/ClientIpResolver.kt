package com.reals.backend.config.security

import com.google.common.net.InetAddresses
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class ClientIpResolver(
    @Value("\${security.client-ip.trusted-proxies:}") trustedProxies: String
) {
    private val trustedPeers = trustedProxies.split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { InetAddresses.forString(it) }
        .toSet()

    fun resolve(request: HttpServletRequest): String? {
        val peer = request.remoteAddr
        val address = peer?.let(::parseAddress) ?: return peer
        if (address in trustedPeers) {
            val headers = request.getHeaders("X-Real-IP")
            if (headers.hasMoreElements()) {
                val client = parseAddress(headers.nextElement().trim())
                if (client != null && !headers.hasMoreElements()) {
                    return InetAddresses.toAddrString(client)
                }
            }
        }
        return InetAddresses.toAddrString(address)
    }

    private fun parseAddress(value: String) =
        value.takeIf { '%' !in it && InetAddresses.isInetAddress(it) }?.let(InetAddresses::forString)
}
