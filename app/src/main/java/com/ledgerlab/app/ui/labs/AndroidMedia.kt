package com.ledgerlab.app.ui.labs

import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.util.LruCache
import com.ledgerlab.app.ui.theme.LabColors
import com.ledgerlab.xrpl.labs.IpfsGateway
import com.ledgerlab.xrpl.wallet.XrplRpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

private const val MAX_MEDIA_BYTES = 25L * 1024 * 1024

/** Opens the system file picker for an image, video or audio file and reads it into memory. */
@Composable
fun rememberMediaPicker(onPicked: (PickedMedia) -> Unit, onError: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val size = c.getLong(1)
                if (size > MAX_MEDIA_BYTES) {
                    onError("That file is over 25 MB. Pick something smaller for a phone upload.")
                    return@rememberLauncherForActivityResult
                }
                c.getString(0)
            } else {
                null
            }
        } ?: "nft-media"
        val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        if (bytes == null) {
            onError("Couldn't read that file.")
            return@rememberLauncherForActivityResult
        }
        onPicked(PickedMedia(name, resolver.getType(uri) ?: "application/octet-stream", bytes))
    }
    return { launcher.launch(arrayOf("image/*", "video/*", "audio/*")) }
}

/** A preview of a picked file: the picture itself for images, otherwise its type. */
@Composable
fun MediaPreview(media: PickedMedia, modifier: Modifier = Modifier) {
    val bitmap = remember(media) {
        if (media.mimeType.startsWith("image/")) decodeSampled(media.bytes, 720)?.asImageBitmap() else null
    }
    MediaBox(bitmap, if (bitmap == null) media.mimeType.substringBefore('/').uppercase() else null, modifier)
}

private val imageCache = LruCache<String, ImageBitmap>(48)

/** Loads an NFT's picture: the URI itself if it's an image, or the `image` in its metadata JSON. */
@Composable
fun NftImage(uri: String?, modifier: Modifier = Modifier) {
    var bitmap by remember(uri) { mutableStateOf(uri?.let { imageCache.get(it) }) }
    var failed by remember(uri) { mutableStateOf(uri.isNullOrBlank()) }
    LaunchedEffect(uri) {
        if (uri.isNullOrBlank() || bitmap != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val url = IpfsGateway.imageUrl(uri) ?: return@runCatching null
                XrplRpc.defaultHttp.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body?.bytes()?.let { decodeSampled(it, 256) }?.asImageBitmap()
                }
            }.getOrNull()
        }
        if (loaded != null) {
            imageCache.put(uri, loaded)
            bitmap = loaded
        } else {
            failed = true
        }
    }
    MediaBox(bitmap, if (bitmap == null) (if (failed) "NFT" else "…") else null, modifier)
}

@Composable
private fun MediaBox(bitmap: ImageBitmap?, label: String?, modifier: Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(LabColors.SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else if (label != null) {
            Text(label, color = LabColors.Magenta, fontSize = 11.sp)
        }
    }
}

/** Decodes at roughly [maxSide] pixels so big photos don't blow the memory budget. */
private fun decodeSampled(bytes: ByteArray, maxSide: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxSide && bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}
