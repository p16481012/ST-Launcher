package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.model.LanAllowedNetwork
import app.tavernbridge.launcher.model.LanSecurityChange
import java.io.ByteArrayOutputStream

/** ASCII JSON escapes avoid constructing an immutable String containing the whole password. */
object LanSecurityPayload {
    fun encode(change: LanSecurityChange): ByteArray {
        if (change.disableOnly) return """{"protocol":2,"action":"disable-listen"}""".toByteArray(Charsets.US_ASCII)
        val output = SecretBuffer()
        try {
            fun raw(text: String) { output.write(text.toByteArray(Charsets.US_ASCII)) }
            fun quoted(characters: CharSequence) {
                raw("\"")
                for (character in characters) when (character) {
                    '"' -> raw("\\\"")
                    '\\' -> raw("\\\\")
                    in ' '..'~' -> output.write(character.code)
                    else -> raw("\\u" + character.code.toString(16).padStart(4, '0'))
                }
                raw("\"")
            }
            val networks = change.allowedNetworks
            require(!change.listen || networks != null)
            require(networks == null || (networks.size <= 66 && networks.all { LanAllowedNetwork.parse(it) != null }))
            raw("{\"protocol\":1,\"listen\":${change.listen},\"allowedNetworks\":")
            if (networks == null) raw("null") else {
                raw("[")
                networks.forEachIndexed { index, value -> if (index > 0) raw(","); quoted(value) }
                raw("]")
            }
            raw(",\"basicAuthEnabled\":${change.basicAuthEnabled},\"credentials\":")
            val credentials = change.credentials
            if (credentials == null) raw("null") else {
                require(credentials.username.isNotBlank() && credentials.username.length <= 128 && ':' !in credentials.username && credentials.username.none(Char::isISOControl))
                require(credentials.password.size in 8..1024 && credentials.password.any { !it.isWhitespace() } && credentials.password.none(Char::isISOControl))
                raw("{\"username\":")
                quoted(credentials.username)
                raw(",\"password\":")
                // CharBuffer wraps the mutable array; it does not create a password String.
                quoted(java.nio.CharBuffer.wrap(credentials.password))
                raw("}")
            }
            raw("}")
            require(output.size() <= 16_384)
            return output.toByteArray()
        } finally { output.clear() }
    }

    private class SecretBuffer : ByteArrayOutputStream(16_384) {
        fun clear() { buf.fill(0); reset() }
    }
}
