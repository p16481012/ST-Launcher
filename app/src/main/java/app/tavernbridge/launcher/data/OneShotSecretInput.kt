package app.tavernbridge.launcher.data

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** One short-lived loopback transfer. Never put the payload itself in Termux's logged Intent/stdin. */
class OneShotSecretInput private constructor(private val payload: ByteArray, lifetimeMillis: Long) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val finished = CountDownLatch(1)
    private val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(lifetimeMillis)
    private val server = ServerSocket().apply {
        try {
            reuseAddress = false
            bind(java.net.InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), 4)
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }
    val port: Int = server.localPort
    val token: String = ByteArray(32).also { SecureRandom().nextBytes(it) }.let { bytes ->
        bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }.also { bytes.fill(0) }
    }
    @Volatile private var activeSocket: Socket? = null
    @Volatile var delivered: Boolean = false
        private set

    init {
        thread(name = "st-secret-deadline", isDaemon = true) {
            if (!finished.await(remainingMillis().toLong(), TimeUnit.MILLISECONDS)) close()
        }
        thread(name = "st-secret-transfer", isDaemon = true) {
            try {
                var attempts = 0
                while (!closed.get() && attempts++ < 16) {
                    val remaining = remainingMillis()
                    if (remaining <= 0) break
                    server.soTimeout = remaining
                    val socket = try { server.accept() } catch (_: Exception) { break }
                    activeSocket = socket
                    try {
                        socket.use {
                            socket.soTimeout = minOf(remainingMillis().coerceAtLeast(1), 2000)
                            val header = readHeader(socket) ?: return@use
                            val firstLine = header.substringBefore("\r\n")
                            val expected = "GET /$token HTTP/1.1".toByteArray(Charsets.US_ASCII)
                            val valid = MessageDigest.isEqual(firstLine.toByteArray(Charsets.US_ASCII), expected)
                            if (!valid || closed.get()) {
                                socket.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                                return@use
                            }
                            // Consume the only authorized request before writing anything.
                            server.close()
                            val output = socket.getOutputStream()
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            output.write(payload)
                            output.flush()
                            delivered = !closed.get()
                        }
                    } catch (_: Exception) {
                        // Network/parser failures never contain credentials in diagnostics.
                    } finally { activeSocket = null }
                    if (server.isClosed) break
                }
            } finally { close() }
        }
    }

    private fun remainingMillis(): Int = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        .coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    private fun readHeader(socket: Socket): String? {
        val bytes = ByteArray(2048)
        var size = 0
        while (size < bytes.size && !closed.get() && remainingMillis() > 0) {
            val value = socket.getInputStream().read()
            if (value < 0 || value == 0 || value > 127) return null
            bytes[size++] = value.toByte()
            if (size >= 4 && bytes[size - 4] == 13.toByte() && bytes[size - 3] == 10.toByte() &&
                bytes[size - 2] == 13.toByte() && bytes[size - 1] == 10.toByte()) {
                return String(bytes, 0, size, Charsets.US_ASCII)
            }
        }
        return null
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { server.close() }
            runCatching { activeSocket?.close() }
            payload.fill(0)
            finished.countDown()
        }
    }

    override fun toString(): String = "OneShotSecretInput([REDACTED])"

    companion object {
        /** Takes ownership of bytes and clears them on success, failure, expiry or explicit close. */
        fun start(payload: ByteArray, lifetimeMillis: Long = 15_000): OneShotSecretInput {
            try {
                require(payload.size in 1..16_384)
                require(lifetimeMillis in 100..15_000)
                return OneShotSecretInput(payload, lifetimeMillis)
            } catch (failure: Throwable) {
                payload.fill(0)
                throw failure
            }
        }
    }
}
