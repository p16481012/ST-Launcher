package app.tavernbridge.launcher.model

data class LanSecuritySnapshot(
    val listen: Boolean = false,
    val whitelist: List<String> = LanWhitelist.loopbacks,
    val basicAuthEnabled: Boolean = false,
    val perUserBasicAuth: Boolean = false,
)

/** Do not put this object in saved state, preferences, logs, diagnostics or a callback Intent. */
class EphemeralBasicAuthCredentials private constructor(
    val username: String,
    val password: CharArray,
) : AutoCloseable {
    override fun close() { password.fill('\u0000') }
    override fun toString(): String = "EphemeralBasicAuthCredentials([REDACTED])"

    companion object {
        fun create(username: String, password: String): EphemeralBasicAuthCredentials? {
            if (!valid(username, password)) return null
            return EphemeralBasicAuthCredentials(username, password.toCharArray())
        }

        fun valid(username: String, password: String): Boolean =
            username.isNotBlank() && username.length <= 128 && ':' !in username &&
                username.none(Char::isISOControl) && password.length in 8..1024 &&
                password.any { !it.isWhitespace() } && password.none(Char::isISOControl)
    }
}

/** Ownership of credentials transfers to the consumer; it must close this in finally. */
class LanSecurityChange private constructor(
    val listen: Boolean,
    // Null is only valid when listen=false: preserve a legacy allow-list while turning exposure off.
    val allowedNetworks: List<String>?,
    val basicAuthEnabled: Boolean,
    val credentials: EphemeralBasicAuthCredentials?,
    internal val disableOnly: Boolean,
) : AutoCloseable {
    constructor(listen: Boolean, allowedNetworks: List<String>?, basicAuthEnabled: Boolean, credentials: EphemeralBasicAuthCredentials?) :
        this(listen, allowedNetworks, basicAuthEnabled, credentials, false)

    companion object {
        /** This command carries no cached authentication or allow-list values. */
        fun disableExposure() = LanSecurityChange(false, null, false, null, true)
    }
    init { require(!listen || allowedNetworks != null) }
    override fun close() { credentials?.close() }
    override fun toString(): String = "LanSecurityChange(listen=$listen, basicAuthEnabled=$basicAuthEnabled, credentials=[REDACTED])"
}
