package app.tavernbridge.launcher.termux

private const val CALLBACK_URI_PREFIX = "st-launcher://termux/callback/"
private val callbackIdentifier = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")

/** Extras do not identify a PendingIntent. Put the request UUID in its data URI too. */
internal fun termuxCallbackUri(callbackId: String): String {
    require(callbackIdentifier.matches(callbackId)) { "Invalid Termux callback identifier" }
    return CALLBACK_URI_PREFIX + callbackId
}

internal fun verifiedTermuxCallbackId(uri: String?, extraId: String?): String? {
    if (uri == null || !uri.startsWith(CALLBACK_URI_PREFIX)) return null
    val id = uri.removePrefix(CALLBACK_URI_PREFIX)
    return id.takeIf { callbackIdentifier.matches(it) && it == extraId }
}

/** In-memory only: a new process must reject callbacks belonging to its predecessor. */
internal class TermuxCallbackRegistry {
    internal class Registration internal constructor(val id: String) {
        internal var delivered = false
        internal var cancel: (() -> Unit)? = null
    }

    private val requests = mutableMapOf<String, Registration>()

    @Synchronized
    fun register(id: String): Registration {
        require(id.isNotBlank())
        check(id !in requests) { "Termux callback is already registered" }
        return Registration(id).also { requests[id] = it }
    }

    @Synchronized
    fun isAwaiting(id: String): Boolean = requests[id]?.delivered == false

    @Synchronized
    fun attachCancellation(id: String, cancel: () -> Unit): Boolean {
        val request = requests[id] ?: return false
        if (request.delivered || request.cancel != null) return false
        request.cancel = cancel
        return true
    }

    /** Claim at most one result, and do not consume a result rejected by the transport. */
    @Synchronized
    fun deliver(id: String, publish: () -> Boolean): Boolean {
        val request = requests[id] ?: return false
        if (request.delivered || !publish()) return false
        request.delivered = true
        return true
    }

    fun unregister(request: Registration) {
        val cancel = synchronized(this) {
            if (requests[request.id] !== request) return
            requests.remove(request.id)
            request.cancel
        }
        // Releasing an Android token is best effort and must not replace a command result.
        runCatching { cancel?.invoke() }
    }
}
