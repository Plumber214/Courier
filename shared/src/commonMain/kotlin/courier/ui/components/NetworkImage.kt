package courier.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import courier.platform.decodeImageByteArray
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val imageCache = mutableMapOf<String, ImageBitmap>()

@Composable
fun NetworkImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: @Composable () -> Unit
) {
    if (url.isNullOrBlank()) {
        placeholder()
        return
    }

    var imageBitmap by remember(url) { mutableStateOf(imageCache[url]) }

    LaunchedEffect(url) {
        if (imageBitmap == null) {
            withContext(Dispatchers.Default) {
                try {
                    val client = HttpClient(CIO)
                    val response = client.get(url)
                    if (response.status == HttpStatusCode.OK) {
                        val bytes = response.bodyAsBytes()
                        val bitmap = decodeImageByteArray(bytes)
                        if (bitmap != null) {
                            imageCache[url] = bitmap
                            imageBitmap = bitmap
                        }
                    }
                    client.close()
                } catch (e: Exception) {
                    // Fallback to placeholder on failure
                }
            }
        }
    }

    if (imageBitmap != null) {
        Image(
            bitmap = imageBitmap!!,
            contentDescription = "Video Thumbnail",
            contentScale = contentScale,
            modifier = modifier
        )
    } else {
        placeholder()
    }
}
