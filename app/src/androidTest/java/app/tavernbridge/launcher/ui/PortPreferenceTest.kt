package app.tavernbridge.launcher.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.tavernbridge.launcher.data.LauncherRepository
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PortPreferenceTest {
    @Test fun portSaveNeedsNoTermuxAndChangesOnlyPort() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "test-port-${UUID.randomUUID()}-"
        val touched = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                touched += prefix + name
                return base.getSharedPreferences(prefix + name, Context.MODE_PRIVATE)
            }
        }
        try {
            val prefs = context.getSharedPreferences("launcher_preferences", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("auto_backup_before_update", false).commit()
            val repo = LauncherRepository(context)
            repo.saveConfiguredPort(8123)
            assertEquals(8123, LauncherRepository(context).configuredPort())
            assertEquals(mapOf("auto_backup_before_update" to false, "server_port" to 8123), prefs.all)
            for (invalid in listOf(0, 1023, 65536)) {
                check(runCatching { repo.saveConfiguredPort(invalid) }.exceptionOrNull() is IllegalArgumentException)
                assertEquals(8123, repo.configuredPort())
            }
        } finally {
            touched.forEach { base.deleteSharedPreferences(it) }
        }
    }
}
