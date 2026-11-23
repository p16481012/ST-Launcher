package app.tavernbridge.launcher.model

import com.google.zxing.qrcode.decoder.Decoder
import org.junit.Assert.assertEquals
import org.junit.Test

class LanQrEncoderTest {
    @Test fun qrRoundTripContainsOnlyTheValidatedUrl() {
        val endpoint = LanEndpoint.parse("192.168.1.20", 8000)!!
        val rendered = LanQrEncoder.encode(endpoint, 512)
        // Decoder consumes the unscaled modules rather than the display bitmap's quiet zone.
        val detected = com.google.zxing.qrcode.detector.Detector(rendered).detect()
        assertEquals(endpoint.url, Decoder().decode(detected.bits).text)
    }

    @Test(expected = IllegalArgumentException::class)
    fun hugeQrAllocationIsRejected() {
        LanQrEncoder.encode(LanEndpoint.parse("10.0.0.1", 8000)!!, 65536)
    }
}
