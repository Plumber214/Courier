package courier.platform

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun decodeImageByteArray(bytes: ByteArray): ImageBitmap? {
    return try {
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
        val origWidth = boundsOptions.outWidth
        val targetWidth = 360
        var sampleSize = 1
        if (origWidth > targetWidth) {
            sampleSize = (origWidth / targetWidth).coerceAtLeast(1)
        }
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        bitmap?.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}
