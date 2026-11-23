package app.tavernbridge.launcher.model

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Fully local QR generation. Only validated, credential-free LAN endpoints are accepted. */
object LanQrEncoder {
    fun encode(endpoint: LanEndpoint, size: Int = 512): BitMatrix {
        require(size in 256..1024)
        return QRCodeWriter().encode(
            endpoint.url, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
    }
}
