package courier.platform

import androidx.compose.ui.graphics.ImageBitmap

expect fun decodeImageByteArray(bytes: ByteArray): ImageBitmap?
