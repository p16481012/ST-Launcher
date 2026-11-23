package app.tavernbridge.launcher.ui

import android.graphics.Bitmap
import app.tavernbridge.launcher.model.LanEndpoint
import app.tavernbridge.launcher.model.LanQrEncoder

object LanQrBitmap {
    fun create(endpoint: LanEndpoint, size: Int = 512): Bitmap {
        val matrix = LanQrEncoder.encode(endpoint, size)
        val pixels = IntArray(size * size) { index ->
            if (matrix[index % size, index / size]) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
