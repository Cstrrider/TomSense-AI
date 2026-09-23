package org.tomsense.android

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Every way into a turn from outside the app.
 *
 * There are five: the launcher, the share sheet, the Quick Settings tile, the
 * `org.tomsense.ASK` intent, and the assistant role. Before this file, three of
 * them were declared in the manifest and silently dropped whatever they were
 * given — MainActivity never read an incoming intent at all, so sharing a link
 * to TomSense opened an empty composer and `adb ... --es text "…"` threw the
 * text away.
 *
 * Routing them all through one shape means a new entry point has to answer
 * only one question — what text, what image, what context — rather than
 * reimplementing the plumbing and getting it subtly wrong.
 */
object Launch {

    /** Text to drop into the composer, unsent. */
    const val EXTRA_PREFILL = "prefill"

    /** An image to attach to the next message. */
    const val EXTRA_IMAGE = "image_uri"

    /**
     * What was on screen when the assistant was invoked.
     *
     * Kept separate from the prefill because it is CONTEXT, not the user's
     * words: it goes to the model as background, never into the composer where
     * it would look like something they typed.
     */
    const val EXTRA_SCREEN_CONTEXT = "screen_context"

    /**
     * Which conversation to open.
     *
     * Without this the app falls back to whichever chat was open last, so
     * every "recent chat" row in the panel landed on the same conversation
     * regardless of which one was tapped.
     */
    const val EXTRA_CONVERSATION = "conversation_id"

    fun openWith(
        context: Context,
        prefill: String? = null,
        imageUri: Uri? = null,
        screenContext: String? = null,
        conversationId: String? = null,
    ) {
        context.startActivity(intent(context, prefill, imageUri, screenContext, conversationId))
    }

    fun intent(
        context: Context,
        prefill: String? = null,
        imageUri: Uri? = null,
        screenContext: String? = null,
        conversationId: String? = null,
    ): Intent = Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        prefill?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_PREFILL, it) }
        conversationId?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_CONVERSATION, it) }
        imageUri?.let {
            putExtra(EXTRA_IMAGE, it)
            // Without this the receiving activity gets a Uri it is not
            // permitted to read, and the attach silently fails.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        screenContext?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_SCREEN_CONTEXT, it) }
    }

    /**
     * Pull whatever an incoming intent carries.
     *
     * Handles both our own extras and a share-sheet `ACTION_SEND`, because
     * from the activity's point of view they are the same thing: some text,
     * maybe an image, from somewhere else.
     */
    fun read(intent: Intent?): Incoming {
        if (intent == null) return Incoming()

        val shared = when (intent.action) {
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            else -> null
        }
        @Suppress("DEPRECATION")
        val sharedImage = when (intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        }

        @Suppress("DEPRECATION")
        return Incoming(
            prefill = intent.getStringExtra(EXTRA_PREFILL) ?: shared,
            imageUri = intent.getParcelableExtra<Uri>(EXTRA_IMAGE) ?: sharedImage,
            screenContext = intent.getStringExtra(EXTRA_SCREEN_CONTEXT),
            conversationId = intent.getStringExtra(EXTRA_CONVERSATION),
        )
    }

    data class Incoming(
        val prefill: String? = null,
        val imageUri: Uri? = null,
        val screenContext: String? = null,
        /** Open THIS conversation rather than whichever was open last. */
        val conversationId: String? = null,
    ) {
        val isEmpty: Boolean
            get() = prefill.isNullOrBlank() && imageUri == null &&
                screenContext.isNullOrBlank() && conversationId.isNullOrBlank()
    }
}
