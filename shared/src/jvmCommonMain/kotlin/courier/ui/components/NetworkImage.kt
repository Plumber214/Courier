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
import courier.platform.deleteFile
import courier.platform.getPlatformActions
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val cacheMutex = Any()
private val imageMemoryCache = object : LinkedHashMap<String, ImageBitmap>(60, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean {
        return size > 60
    }
}
private val inFlightRequests = mutableMapOf<String, CompletableDeferred<ImageBitmap?>>()

private fun getCachedMemoryImage(url: String): ImageBitmap? {
    return synchronized(cacheMutex) {
        imageMemoryCache[url]
    }
}

private fun putCachedMemoryImage(url: String, bitmap: ImageBitmap) {
    synchronized(cacheMutex) {
        imageMemoryCache[url] = bitmap
    }
}

/** Total bytes the thumbnail cache may occupy on disk before eviction. */
private const val DISK_CACHE_BUDGET_BYTES = 48L * 1024 * 1024

/** Trimming lists the directory, so it runs periodically rather than per write. */
private const val WRITES_BETWEEN_TRIMS = 25

private val writesSinceTrim = java.util.concurrent.atomic.AtomicInteger(0)

private fun thumbCacheDir(): File? {
    return try {
        val cacheDir = File(getPlatformActions().getAppStorageDirectory(), "thumb_cache")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        cacheDir
    } catch (_: Exception) {
        null
    }
}

private fun getDiskCacheFile(url: String): File? {
    val cacheDir = thumbCacheDir() ?: return null
    return try {
        // SHA-256 rather than String.hashCode(). A 32-bit hash collides often
        // enough to matter across a long download history, and a collision here
        // means showing one video's thumbnail on another.
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
        val name = digest.take(16).joinToString("") { "%02x".format(it) }
        File(cacheDir, "thumb_$name.cache")
    } catch (_: Exception) {
        null
    }
}

/**
 * Evicts least-recently-modified entries until the cache fits its budget.
 *
 * Nothing evicted this directory before, so it grew for the life of the
 * install — one entry per thumbnail ever fetched.
 */
private fun trimDiskCacheIfNeeded() {
    if (writesSinceTrim.incrementAndGet() < WRITES_BETWEEN_TRIMS) return
    writesSinceTrim.set(0)

    try {
        val cacheDir = thumbCacheDir() ?: return
        val files = cacheDir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_CACHE_BUDGET_BYTES) return

        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= DISK_CACHE_BUDGET_BYTES) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    } catch (_: Exception) {
    }
}

private val httpClient by lazy {
    HttpClient(CIO) {
        engine {
            requestTimeout = 15_000
        }
    }
}

private suspend fun loadThumbnail(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    // 1. Check memory cache
    val mem = getCachedMemoryImage(url)
    if (mem != null) return@withContext mem

    // 2. Check in-flight deduplication
    var myDeferred: CompletableDeferred<ImageBitmap?>? = null
    val existingDeferred: CompletableDeferred<ImageBitmap?>?
    synchronized(cacheMutex) {
        val current = inFlightRequests[url]
        if (current != null) {
            existingDeferred = current
        } else {
            existingDeferred = null
            myDeferred = CompletableDeferred()
            inFlightRequests[url] = myDeferred!!
        }
    }

    if (existingDeferred != null) {
        return@withContext existingDeferred.await()
    }

    // 3. Check disk cache
    val diskFile = getDiskCacheFile(url)
    if (diskFile != null && diskFile.exists() && diskFile.length() > 0) {
        try {
            val bytes = diskFile.readBytes()
            val bitmap = decodeImageByteArray(bytes)
            if (bitmap != null) {
                putCachedMemoryImage(url, bitmap)
                synchronized(cacheMutex) { inFlightRequests.remove(url) }
                myDeferred?.complete(bitmap)
                return@withContext bitmap
            }
        } catch (_: Exception) {}
    }

    // 4. Network fetch
    var fetchedBitmap: ImageBitmap? = null
    try {
        val response = httpClient.get(url)
        if (response.status == HttpStatusCode.OK) {
            val bytes = response.bodyAsBytes()
            val bitmap = decodeImageByteArray(bytes)
            if (bitmap != null) {
                putCachedMemoryImage(url, bitmap)
                if (diskFile != null) {
                    diskFile.writeBytes(bytes)
                    trimDiskCacheIfNeeded()
                }
                fetchedBitmap = bitmap
            }
        }
    } catch (_: Exception) {
    } finally {
        synchronized(cacheMutex) { inFlightRequests.remove(url) }
        myDeferred?.complete(fetchedBitmap)
    }

    fetchedBitmap
}

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

    var imageBitmap by remember(url) { mutableStateOf(getCachedMemoryImage(url)) }

    LaunchedEffect(url) {
        if (imageBitmap == null) {
            val loaded = loadThumbnail(url)
            if (loaded != null) {
                imageBitmap = loaded
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
