package org.tomsense.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream

/**
 * Turning a picked file into something worth uploading.
 *
 * Images are downscaled and re-encoded before they leave the device, for the
 * same reason stable does it (`uploads.IMAGE_MAX_EDGE`): an attachment reaches
 * a vision model as base64 inside the request body, and a 12-megapixel phone
 * photo is several megabytes of it. The model gains nothing from the extra
 * pixels and the request pays for every one of them.
 *
 * Non-images are passed through untouched — there is nothing useful to do to a
 * PDF here, and re-encoding one would only corrupt it.
 */
object Attachments {

    /** Longest edge, matching stable. Gemma-class vision gains nothing above this. */
    private const val MAX_EDGE = 1600
    private const val JPEG_QUALITY = 85

    data class Prepared(val bytes: ByteArray, val mime: String, val name: String)

    fun prepare(context: Context, uri: Uri): Prepared? {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val name = displayName(context, uri) ?: "file"

        val raw = runCatching {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        if (raw.isEmpty()) return null

        if (!mime.startsWith("image/")) return Prepared(raw, mime, name)

        val scaled = downscale(raw) ?: return Prepared(raw, mime, name)
        // Always JPEG after re-encoding, whatever went in — the extension has
        // to follow or the edge stores a mislabelled object.
        return Prepared(scaled, "image/jpeg", name.substringBeforeLast('.') + ".jpg")
    }

    /**
     * Decode at a sample size first, then scale exactly.
     *
     * `inSampleSize` is what keeps a large photo from being fully decoded into
     * memory before being shrunk — decoding a 50MP image at full size to
     * produce a 1600px one is how a picker crashes a phone with an OOM.
     */
    private fun downscale(raw: ByteArray): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return@runCatching null

        var sample = 1
        while (longest / sample > MAX_EDGE * 2) sample *= 2

        val decoded = BitmapFactory.decodeByteArray(
            raw, 0, raw.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return@runCatching null

        val edge = maxOf(decoded.width, decoded.height)
        val bitmap = if (edge > MAX_EDGE) {
            val ratio = MAX_EDGE.toFloat() / edge
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * ratio).toInt().coerceAtLeast(1),
                (decoded.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            decoded
        }

        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
    }.getOrNull()

    private fun displayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
}
