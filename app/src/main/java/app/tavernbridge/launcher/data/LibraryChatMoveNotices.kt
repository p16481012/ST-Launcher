package app.tavernbridge.launcher.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class LibraryChatMove { RENAMED, TRASHED }

/** No chat names or paths are persisted. A UI timeout is never an acknowledgement. */
internal class LibraryChatMoveNotices(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("library_chat_advice", Context.MODE_PRIVATE))
    private val value = MutableStateFlow(LibraryChatMove.entries.find { it.name == prefs.getString("pending", null) })
    val pending = value.asStateFlow()
    fun record(kind: LibraryChatMove) {
        prefs.edit().putString("pending", kind.name).apply()
        value.value = kind
    }
    fun acknowledge(kind: LibraryChatMove) {
        if (!value.compareAndSet(kind, null)) return
        prefs.edit().remove("pending").apply()
    }
}
