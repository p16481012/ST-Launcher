package app.tavernbridge.launcher.ui

import androidx.test.platform.app.InstrumentationRegistry
import app.tavernbridge.launcher.data.LauncherRepository
import app.tavernbridge.launcher.model.probeWarmServer
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class WarmReturnLoopbackTest {
    @Test fun healthyWarmReturnUsesLoopbackWithoutFullInspection() = runBlocking {
        val repo = LauncherRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        for ((status, expectedInspections) in listOf("200 OK" to 0, "401 Unauthorized" to 0, "503 Unavailable" to 1)) {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 5_000
                val responder = async(Dispatchers.IO) {
                    server.accept().use { client ->
                        client.soTimeout = 5_000
                        val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val request = reader.readLine()
                        while (!reader.readLine().isNullOrEmpty()) { /* consume headers */ }
                        client.getOutputStream().write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        request
                    }
                }
                var inspections = 0
                probeWarmServer({ repo.pingServer(server.localPort) }, { true }, { inspections++ })
                assertEquals("GET / HTTP/1.1", responder.await())
                assertEquals(status, expectedInspections, inspections)
            }
        }
    }
}
