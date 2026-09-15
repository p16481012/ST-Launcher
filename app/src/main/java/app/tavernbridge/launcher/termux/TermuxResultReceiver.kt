package app.tavernbridge.launcher.termux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) return

        val resultBundle = intent.getBundleExtra(TermuxContract.EXTRA_RESULT_BUNDLE)
        val result = TermuxCommandResult(
            callbackId = intent.getStringExtra(TermuxContract.EXTRA_CALLBACK_ID).orEmpty(),
            stdout = resultBundle?.getString("stdout")
                ?: intent.getStringExtra("stdout").orEmpty(),
            stderr = resultBundle?.getString("stderr")
                ?: intent.getStringExtra("stderr").orEmpty(),
            exitCode = resultBundle?.getInt("exitCode", -1)
                ?: intent.getIntExtra("exitCode", -1),
            errorCode = resultBundle?.getInt("err", 0)
                ?: intent.getIntExtra("err", 0),
            errorMessage = resultBundle?.getString("errmsg")
                ?: intent.getStringExtra("errmsg")
                ?: "Termux 실행 결과 Bundle을 읽지 못했습니다. Termux 0.109 이상인지 확인해 주세요.",
            stdoutOriginalLength = (resultBundle?.getString("stdout_original_length")
                ?: intent.getStringExtra("stdout_original_length"))?.toIntOrNull() ?: -1,
        )
        TermuxResultBus.publish(result)
    }
}
