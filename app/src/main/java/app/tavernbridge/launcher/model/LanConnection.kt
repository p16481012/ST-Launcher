package app.tavernbridge.launcher.model

/** Literal-only parsing: never resolves a hostname or contacts the network. */
class LanAllowedNetwork private constructor(
    val canonical: String,
    val prefixLength: Int,
    val isLoopback: Boolean,
    val isIpv6: Boolean,
) {
    companion object {
        fun parse(raw: String): LanAllowedNetwork? {
            val value = raw.trim()
            if (value.isEmpty() || value.length > 80 || value.any { it.isWhitespace() }) return null
            val parts = value.split('/')
            if (parts.size !in 1..2) return null
            val address = LiteralIp.parse(parts[0]) ?: return null
            val bits = address.size * 8
            val prefix = if (parts.size == 1) bits else {
                if (!parts[1].matches(Regex("0|[1-9][0-9]{0,2}"))) return null
                parts[1].toIntOrNull()?.takeIf { it in 0..bits } ?: return null
            }
            val minimum = LiteralIp.privatePrefix(address) ?: return null
            if (prefix < minimum) return null // The whole CIDR, not just its first address, must be private.
            val network = address.copyOf()
            for (index in network.indices) {
                val retained = (prefix - index * 8).coerceIn(0, 8)
                network[index] = network[index] and (0xff shl (8 - retained)) and 0xff
            }
            val host = LiteralIp.format(network)
            return LanAllowedNetwork(
                canonical = if (parts.size == 1) host else "$host/$prefix",
                prefixLength = prefix,
                isLoopback = LiteralIp.isLoopback(address),
                isIpv6 = address.size == 16,
            )
        }
    }
}

/** A credential-free private LAN URL. Cannot represent public hosts, user-info, paths or queries. */
class LanEndpoint private constructor(val host: String, val port: Int, val url: String) {
    companion object {
        fun parse(host: String, port: Int): LanEndpoint? {
            if (port !in 1..65535) return null
            val address = LiteralIp.parse(host.trim()) ?: return null
            if (LiteralIp.privatePrefix(address) == null || LiteralIp.isLoopback(address)) return null
            val canonical = LiteralIp.format(address)
            val authority = if (address.size == 16) "[$canonical]" else canonical
            return LanEndpoint(canonical, port, "http://$authority:$port/")
        }
    }
}

object LanWhitelist {
    val loopbacks: List<String> = listOf("127.0.0.1", "::1")

    /** Null means invalid, not an empty allow-list. Loopback access is always retained. */
    fun parse(text: String): List<String>? {
        if (text.length > 8192) return null
        val entries = text.split(',', '\n').map(String::trim).filter(String::isNotEmpty)
        if (entries.size > 64) return null
        val parsed = entries.map { LanAllowedNetwork.parse(it)?.canonical ?: return null }
        return (loopbacks + parsed).distinct()
    }
}

private object LiteralIp {
    fun parse(value: String): IntArray? {
        if (value.isEmpty() || value.length > 45 || value.any { it.isWhitespace() }) return null
        if (!value.contains(':')) {
            val parts = value.split('.')
            if (parts.size != 4) return null
            val octets = parts.map {
                if (!it.matches(Regex("0|[1-9][0-9]{0,2}"))) return null
                it.toIntOrNull()?.takeIf { part -> part in 0..255 } ?: return null
            }
            return octets.toIntArray()
        }
        // Scope IDs and IPv4-mapped forms are intentionally unsupported; they can obscure scope.
        if (value.any { it != ':' && it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' }) return null
        if (value.contains(":::")) return null
        val compression = value.indexOf("::")
        if (compression != value.lastIndexOf("::")) return null
        fun groups(part: String): List<Int>? {
            if (part.isEmpty()) return emptyList()
            return part.split(':').map {
                if (it.length !in 1..4) return null
                it.toIntOrNull(16) ?: return null
            }
        }
        val words: List<Int>
        if (compression >= 0) {
            val left = groups(value.substring(0, compression)) ?: return null
            val right = groups(value.substring(compression + 2)) ?: return null
            if (left.size + right.size >= 8) return null
            words = left + List(8 - left.size - right.size) { 0 } + right
        } else {
            words = groups(value) ?: return null
            if (words.size != 8) return null
        }
        return words.flatMap { listOf(it shr 8, it and 0xff) }.toIntArray()
    }

    fun isLoopback(ip: IntArray): Boolean = if (ip.size == 4) ip[0] == 127
    else ip.take(15).all { it == 0 } && ip[15] == 1

    fun privatePrefix(ip: IntArray): Int? = if (ip.size == 4) when {
        ip[0] == 127 -> 8
        ip[0] == 10 -> 8
        ip[0] == 172 && ip[1] in 16..31 -> 12
        ip[0] == 192 && ip[1] == 168 -> 16
        else -> null
    } else when {
        isLoopback(ip) -> 128
        ip[0] and 0xfe == 0xfc -> 7
        else -> null
    }

    fun format(ip: IntArray): String = if (ip.size == 4) ip.joinToString(".") else {
        if (isLoopback(ip)) "::1" else ip.toList().chunked(2).joinToString(":") { ((it[0] shl 8) or it[1]).toString(16) }
    }
}
