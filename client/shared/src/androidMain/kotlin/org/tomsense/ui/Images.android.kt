package org.tomsense.ui

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun decodeImageBytes(bytes: ByteArray): ImageBitmap? =
    // Returns null rather than throwing on a truncated or non-image payload:
    // a broken attachment should leave a placeholder in the transcript, not
    // take down the whole conversation view.
    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
        .getOrNull()
