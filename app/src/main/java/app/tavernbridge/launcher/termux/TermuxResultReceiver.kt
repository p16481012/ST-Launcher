package app.tavernbridge.launcher.termux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) return
        val callbackId = verifiedTermuxCallbackId(
            intent.dataString, intent.getStringExtra(TermuxContract.EXTRA_CALLBACK_ID),
        ) ?: return
        if (!TermuxResultBus.callbacks.isAwaiting(callbackId)) return

        val resultBundle = intent.getBundleExtra(TermuxContract.EXTRA_RESULT_BUNDLE)
        val result = TermuxResultDecoder.decode(
            callbackId = callbackId,
            bundle = resultBundle?.resultFields(),
            legacyExtras = intent.extras?.resultFields().orEmpty(),
        )
        TermuxResultBus.publish(result)
    }
}

// Keep the Android boundary small; the decoder validates types and required fields.
@Suppress("DEPRECATION")
private fun Bundle.resultFields(): Map<String, Any?> = TermuxResultDecoder.keys.associateWith { get(it) }
