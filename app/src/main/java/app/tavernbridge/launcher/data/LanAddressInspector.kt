package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.model.LanEndpoint
import java.net.NetworkInterface

/** Read-only interface enumeration, with no DNS, socket connection, Wi-Fi permission or persistence. */
class LanAddressInspector {
    fun inspect(port: Int): List<LanEndpoint> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .take(64)
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().take(32) }
            .mapNotNull { address -> address.hostAddress?.let { LanEndpoint.parse(it, port) } }
            .distinctBy(LanEndpoint::url)
            .take(16)
    } catch (_: Exception) {
        emptyList()
    }
}
