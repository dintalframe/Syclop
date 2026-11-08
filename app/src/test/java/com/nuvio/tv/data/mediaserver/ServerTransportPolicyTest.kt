package com.nuvio.tv.data.mediaserver

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerTransportPolicyTest {
    @Test
    fun httpIsLimitedToExplicitlyApprovedLocalServers() {
        assertEquals("192.168.1.10", ServerTransportPolicy.localHttpHost("192.168.1.10:8096"))
        assertEquals("den.local", ServerTransportPolicy.localHttpHost("http://den.local:8096"))
        assertEquals("nas", ServerTransportPolicy.localHttpHost("nas:8096"))
        assertEquals("jellyfin.home.arpa", ServerTransportPolicy.localHttpHost("http://jellyfin.home.arpa:8096"))
        assertEquals("media.private.example", ServerTransportPolicy.localHttpHost("http://media.private.example:8096"))
        assertNull(ServerTransportPolicy.localHttpHost("http://8.8.8.8"))
        assertNull(ServerTransportPolicy.localHttpHost("https://192.168.1.10"))
        assertFalse(ServerTransportPolicy.allowsCredentials("http://192.168.1.10:8096", false))
        assertTrue(ServerTransportPolicy.allowsCredentials("http://192.168.1.10:8096", true))
        assertFalse(ServerTransportPolicy.allowsCredentials("http://8.8.8.8", true))
        assertTrue(ServerTransportPolicy.allowsCredentials("http://jellyfin.home.arpa:8096", true))
        assertTrue(ServerTransportPolicy.allowsCredentials("https://public.example", false))
    }

    @Test
    fun localDnsClientRejectsPublicDestinations() {
        assertTrue(ServerTransportPolicy.isLocalAddress(InetAddress.getByName("192.168.1.10")))
        assertTrue(ServerTransportPolicy.isLocalAddress(InetAddress.getByName("fd12::1")))
        assertFalse(ServerTransportPolicy.isLocalAddress(InetAddress.getByName("8.8.8.8")))
        assertFalse(ServerTransportPolicy.isLocalAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }
}
