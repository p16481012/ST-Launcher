package app.tavernbridge.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanConnectionTest {
    @Test fun privateIpv4AndLoopbackAreAccepted() {
        for (input in listOf("10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.10.20", "127.0.0.1")) {
            assertEquals(input, LanAllowedNetwork.parse(input)?.canonical)
        }
        assertTrue(LanAllowedNetwork.parse("127.0.0.1")!!.isLoopback)
        assertFalse(LanAllowedNetwork.parse("10.0.0.1")!!.isLoopback)
    }

    @Test fun subnetCannotExpandBeyondPrivateRange() {
        assertEquals("192.168.10.0/24", LanAllowedNetwork.parse("192.168.10.87/24")?.canonical)
        assertEquals("10.0.0.0/8", LanAllowedNetwork.parse("10.1.2.3/8")?.canonical)
        assertEquals("172.16.0.0/12", LanAllowedNetwork.parse("172.31.1.1/12")?.canonical)
        for (input in listOf("10.1.2.3/7", "172.16.1.1/11", "192.168.1.1/15", "127.0.0.1/0")) {
            assertNull(input, LanAllowedNetwork.parse(input))
        }
    }

    @Test fun malformedPublicAndAmbiguousInputsAreRejectedWithoutDns() {
        for (input in listOf(
            "*", "0.0.0.0/0", "8.8.8.8", "172.15.1.1", "172.32.1.1", "192.169.1.1", "169.254.1.1",
            "localhost", "example.com", "10.1", "10.01.2.3", "0x0a.1.2.3", "10.256.2.3", "-10.1.2.3",
            "http://10.1.2.3", "user:pass@10.1.2.3", "10.1.2.3:8000", "10.1.2.3/33", "10.1.2.3/-1",
            "10.1.2.3/08", "10.1.2.3/8/8", "10.1.2.3\n/32", "", "10.1.2.3;ls",
        )) assertNull(input, LanAllowedNetwork.parse(input))
    }

    @Test fun ipv6SupportsOnlyUlaAndExactLoopback() {
        assertEquals("::1", LanAllowedNetwork.parse("::1")?.canonical)
        assertEquals("::1/128", LanAllowedNetwork.parse("0:0:0:0:0:0:0:1/128")?.canonical)
        assertEquals("fd12:3456:0:0:0:0:0:0/64", LanAllowedNetwork.parse("FD12:3456::abcd/64")?.canonical)
        assertEquals("fc00:0:0:0:0:0:0:0/7", LanAllowedNetwork.parse("fd12::1/7")?.canonical)
        for (input in listOf(
            "::", "::/0", "::1/127", "2001:4860::8888", "fe80::1", "fe80::1%wlan0", "::ffff:192.168.1.1",
            "fd12::1/6", "fd12::1/129", "[fd12::1]", "fd12:::1", "fd12::1::2", "fd12:1:2:3:4:5:6", "fd12:1:2:3:4:5:6:7:8",
        )) assertNull(input, LanAllowedNetwork.parse(input))
    }

    @Test fun allowlistAlwaysIncludesLoopbacksAndIsBounded() {
        assertEquals(LanWhitelist.loopbacks, LanWhitelist.parse(""))
        assertEquals(listOf("127.0.0.1", "::1", "192.168.1.0/24"), LanWhitelist.parse("192.168.1.20/24,127.0.0.1\n::1"))
        assertNull(LanWhitelist.parse("192.168.1.1\n*"))
        assertNull(LanWhitelist.parse(List(65) { "192.168.1.1" }.joinToString("\n")))
        assertNull(LanWhitelist.parse("x".repeat(8193)))
    }

    @Test fun qrEndpointsCannotContainCredentialsOrPublicHost() {
        assertEquals("http://192.168.1.20:8000/", LanEndpoint.parse("192.168.1.20", 8000)?.url)
        assertEquals("http://[fd12:0:0:0:0:0:0:1]:65535/", LanEndpoint.parse("fd12::1", 65535)?.url)
        for (input in listOf("127.0.0.1", "::1", "8.8.8.8", "user:pass@192.168.1.20", "192.168.1.20/24", "localhost")) {
            assertNull(LanEndpoint.parse(input, 8000))
        }
        assertNull(LanEndpoint.parse("192.168.1.20", 0))
        assertNull(LanEndpoint.parse("192.168.1.20", 65536))
        assertNotNull(LanEndpoint.parse("10.0.0.1", 1))
    }
}
