package com.salmansaadiq.dheu.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Same pipeline as the website's src/lib/images.ts: decode (HEIC included,
// EXIF rotation applied), resize to fit a box, flatten onto white and
// re-encode as JPEG. Re-encoding drops every bit of metadata (GPS etc.).
object Images {
    data class Spec(val maxW: Int, val maxH: Int, val quality: Int, val square: Boolean = false)

    val POST = Spec(1080, 1920, 90)
    val POST_THUMB = Spec(480, 480, 80, square = true) // profile-grid tile
    val STORY = Spec(1080, 1920, 90)
    val AVATAR = Spec(320, 320, 88, square = true)

    class Encoded(val bytes: ByteArray, val width: Int, val height: Int)

    /** A post photo and its grid thumbnail, cut from one decode. */
    class PostPhoto(val photo: Encoded, val thumb: Encoded)

    suspend fun encode(context: Context, uri: Uri, spec: Spec): Encoded = withContext(Dispatchers.Default) {
        val source = decode(context, uri)
        try {
            render(source, spec)
        } finally {
            source.recycle()
        }
    }

    suspend fun encodePost(context: Context, uri: Uri): PostPhoto = withContext(Dispatchers.Default) {
        val source = decode(context, uri)
        try {
            PostPhoto(render(source, POST), render(source, POST_THUMB))
        } finally {
            source.recycle()
        }
    }

    /** "<user>/<uuid>.jpg", the layout Storage policies expect (own folder only). */
    fun newPath(userId: String) = "$userId/${UUID.randomUUID()}.jpg"

    /** "<user>/<uuid>.jpg" -> "<user>/<uuid>-thumb.jpg", next to its photo. */
    fun thumbPathFor(path: String) = path.removeSuffix(".jpg") + "-thumb.jpg"

    private fun decode(context: Context, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return try {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                // Decode no larger than twice the output so huge camera files don't run out of memory.
                val longest = max(info.size.width, info.size.height)
                if (longest > 2 * 1920) {
                    val scale = 2 * 1920.0 / longest
                    decoder.setTargetSize((info.size.width * scale).roundToInt(), (info.size.height * scale).roundToInt())
                }
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Couldn't read that image.", e)
        }
    }

    private fun render(src: Bitmap, spec: Spec): Encoded {
        var sx = 0
        var sy = 0
        var sw = src.width
        var sh = src.height
        if (spec.square) {
            val side = min(sw, sh)
            sx = (sw - side) / 2
            sy = (sh - side) / 2
            sw = side
            sh = side
        }
        val scale = min(1.0, min(spec.maxW.toDouble() / sw, spec.maxH.toDouble() / sh))
        val outW = max(1, (sw * scale).roundToInt())
        val outH = max(1, (sh * scale).roundToInt())

        val out = createBitmap(outW, outH)
        Canvas(out).apply {
            drawColor(Color.WHITE) // JPEG has no alpha: flatten transparent images onto white
            drawBitmap(src, Rect(sx, sy, sx + sw, sy + sh), Rect(0, 0, outW, outH), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val bytes = ByteArrayOutputStream().use { stream ->
            out.compress(Bitmap.CompressFormat.JPEG, spec.quality, stream)
            stream.toByteArray()
        }
        out.recycle()
        return Encoded(bytes, outW, outH)
    }
}
