package org.tomsense.ui

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decode image bytes for display.
 *
 * Platform-specific because there is no common decoder: Android has
 * BitmapFactory, the JVM desktop target has Skia. Kept to this one function so
 * the rest of the image path — fetching, caching, layout — stays shared.
 */
expect fun decodeImageBytes(bytes: ByteArray): ImageBitmap?
