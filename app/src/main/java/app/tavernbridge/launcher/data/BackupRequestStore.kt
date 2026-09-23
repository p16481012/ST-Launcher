package app.tavernbridge.launcher.data

import android.content.Context
import app.tavernbridge.launcher.model.BackupRequest
import app.tavernbridge.launcher.model.BackupRequestCodec

internal class BackupRequestStore(context: Context) {
    private val preferences = context.getSharedPreferences("backup_request", Context.MODE_PRIVATE)

    fun load(): BackupRequest? = BackupRequestCodec.decode(preferences.getString("request", null))

    fun save(request: BackupRequest) {
        check(preferences.edit().putString("request", BackupRequestCodec.encode(request)).commit()) {
            "백업 요청을 저장하지 못했습니다. 저장 공간을 확인한 뒤 다시 시도해 주세요."
        }
    }
}
