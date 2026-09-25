package org.tomsense.android.feed

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json

/**
 * The last feed and its thumbnails, on disk.
 *
 * The panel's state used to live only in memory, so whenever Android killed
 * the process the next open was a blank card until the network answered.
 * With this the previous feed paints immediately and the fetch replaces it.
 *
 * Everything lives under cacheDir, so the system may clear it under storage
 * pressure — which is fine, a miss just means the old behaviour.
 */
object FeedCache {
    private val json = Json { ignoreUnknownKeys = true }

    /** Thumbnails outlive the 72h article window by a little, then go. */
    private const val THUMB_MAX_AGE_MS = 4L * 24 * 60 * 60 * 1000

    private fun feedFile(ctx: Context) = File(ctx.cacheDir, "feed.json")
    private fun thumbDir(ctx: Context) = File(ctx.cacheDir, "thumbs").apply { mkdirs() }

    fun loadFeed(ctx: Context): NewsClient.Feed? = runCatching {
        json.decodeFromString(NewsClient.Feed.serializer(), feedFile(ctx).readText())
    }.getOrNull()

    fun saveFeed(ctx: Context, feed: NewsClient.Feed) {
        runCatching {
            // Write-then-rename, so a process killed mid-write never leaves a
            // truncated file that fails to parse on the next open.
            val tmp = File(ctx.cacheDir, "feed.json.tmp")
            tmp.writeText(json.encodeToString(NewsClient.Feed.serializer(), feed))
            tmp.renameTo(feedFile(ctx))
        }
    }

    private fun thumbFile(ctx: Context, imageUrl: String, width: Int): File {
        val digest = MessageDigest.getInstance("SHA-1").digest("$width|$imageUrl".toByteArray())
        return File(thumbDir(ctx), digest.joinToString("") { "%02x".format(it) })
    }

    fun loadThumb(ctx: Context, imageUrl: String, width: Int): ByteArray? =
        thumbFile(ctx, imageUrl, width).takeIf { it.exists() }?.let { runCatching { it.readBytes() }.getOrNull() }

    fun saveThumb(ctx: Context, imageUrl: String, width: Int, bytes: ByteArray) {
        runCatching { thumbFile(ctx, imageUrl, width).writeBytes(bytes) }
    }

    /** Drop thumbnails for articles that have long since left the feed. */
    fun pruneThumbs(ctx: Context) {
        val cutoff = System.currentTimeMillis() - THUMB_MAX_AGE_MS
        thumbDir(ctx).listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }
}
