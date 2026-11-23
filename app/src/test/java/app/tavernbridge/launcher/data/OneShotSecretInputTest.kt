package app.tavernbridge.launcher.data

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OneShotSecretInputTest {
    private fun request(port: Int, token: String, extraHeader: String = ""): String = Socket().use { socket ->
        socket.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port), 1000)
        socket.soTimeout = 1500
        socket.getOutputStream().write("GET /$token HTTP/1.1\r\nHost: 127.0.0.1\r\n$extraHeader\r\n".toByteArray(Charsets.US_ASCII))
        socket.getInputStream().readBytes().toString(Charsets.UTF_8)
    }

    private fun awaitCleared(payload: ByteArray) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (payload.any { it != 0.toByte() } && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(payload.all { it == 0.toByte() })
    }

    @Test fun oneRequestReceivesPayloadThenPortClosesAndBytesAreCleared() {
        val payload = "{\"password\":\"fixture-secret\"}".toByteArray()
        OneShotSecretInput.start(payload).use { source ->
            assertEquals(64, source.token.length)
            val response = request(source.port, source.token)
            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertEquals("{\"password\":\"fixture-secret\"}", response.substringAfter("\r\n\r\n"))
            awaitCleared(payload)
            assertTrue(source.delivered)
            assertTrue(runCatching { request(source.port, source.token) }.isFailure)
        }
    }

    @Test fun wrongTokenReceivesNoSecretAndDoesNotConsumeValidRequest() {
        val payload = "fixture-secret".toByteArray()
        OneShotSecretInput.start(payload).use { source ->
            val denied = request(source.port, "0".repeat(64))
            assertTrue(denied.startsWith("HTTP/1.1 403"))
            assertFalse(denied.contains("fixture-secret"))
            assertFalse(source.toString().contains(source.token))
            assertTrue(request(source.port, source.token).endsWith("fixture-secret"))
            awaitCleared(payload)
        }
    }

    @Test fun expiryAndExplicitCloseClearUnsentPayloads() {
        val expired = "fixture-secret".toByteArray()
        OneShotSecretInput.start(expired, 100).use { source ->
            awaitCleared(expired)
            assertFalse(source.delivered)
            assertTrue(runCatching { request(source.port, source.token) }.isFailure)
        }
        val cancelled = "fixture-secret".toByteArray()
        OneShotSecretInput.start(cancelled).close()
        awaitCleared(cancelled)
    }

    @Test fun oversizedInputIsRejectedAndCleared() {
        val payload = ByteArray(16385) { 1 }
        assertTrue(runCatching { OneShotSecretInput.start(payload) }.isFailure)
        assertTrue(payload.all { it == 0.toByte() })
    }

    @Test fun oversizedHeadersNeverReceivePayload() {
        val payload = "fixture-secret".toByteArray()
        OneShotSecretInput.start(payload).use { source ->
            val response = runCatching { request(source.port, source.token, "X-Long: " + "a".repeat(3000) + "\r\n") }.getOrNull()
            assertTrue(response == null || !response.contains("fixture-secret"))
            assertFalse(source.delivered)
        }
        awaitCleared(payload)
    }
}
