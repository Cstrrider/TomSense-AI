package org.tomsense.tools

/**
 * Tier 0: the commands that should never touch the network.
 *
 * The original plan was an on-device LLM (Gemma nano via MediaPipe) for this.
 * That dependency shipped 8.4 MB of native inference engine and was never
 * referenced by a line of code, so it has been removed. What it was FOR is
 * worth keeping: "set a timer for ten minutes" answered instantly, offline,
 * for free, rather than making a round trip to Cloudflare to be told to call
 * a tool this device already has.
 *
 * This is deterministic matching rather than a model. That is a real
 * trade — no paraphrase, no ambiguity, no "turn the lights down a bit" —
 * and in exchange it is instant, costs nothing, cannot hallucinate a tool
 * call, and needs no multi-gigabyte download. An on-device model can still
 * slot in above this later; the routing seam is the same.
 *
 * ## The rule that matters
 *
 * **Anything not matched with high confidence falls through to the model.**
 * A false positive here is far worse than a miss: a miss costs a round trip,
 * whereas a false positive hijacks a message the user meant as conversation
 * and answers it with a timer. Every pattern below is anchored and specific
 * for that reason, and anything with trailing words it cannot account for is
 * rejected.
 */

/** A locally resolved command: which device tool, and with what arguments. */
data class LocalIntent(
    val tool: String,
    val args: Map<String, Any>,
    /** Shown in the transcript so a local answer is never mistaken for a model's. */
    val summary: String,
)

private val NUMBER_WORDS = mapOf(
    "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
    "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
    "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40,
    "forty-five" to 45, "fifty" to 50, "sixty" to 60, "ninety" to 90,
    "half" to 30,
)

private fun number(raw: String): Int? =
    raw.toIntOrNull() ?: NUMBER_WORDS[raw.lowercase()]

/**
 * Resolve a message locally, or return null to let the model handle it.
 *
 * Case- and punctuation-insensitive, but otherwise strict.
 */
fun matchLocalIntent(input: String): LocalIntent? {
    val text = input.trim().lowercase().trimEnd('.', '!', '?').trim()
    if (text.isEmpty() || text.length > 60) return null

    return timer(text)
        ?: alarm(text)
        ?: volume(text)
        ?: brightness(text)
        ?: media(text)
}

// ─── timer ──────────────────────────────────────────────────────────────────

private val TIMER = Regex(
    """^(?:set |start )?(?:a )?timer (?:for )?([\w-]+) ?(second|sec|minute|min|hour|hr)s?$""",
)

private fun timer(text: String): LocalIntent? {
    val m = TIMER.find(text) ?: return null
    val n = number(m.groupValues[1]) ?: return null
    if (n <= 0) return null

    val seconds = when (m.groupValues[2]) {
        "second", "sec" -> n
        "minute", "min" -> n * 60
        else -> n * 3600
    }
    // A day is the practical ceiling; anything beyond is likelier a
    // misparse than a real request.
    if (seconds > 86_400) return null

    val unit = m.groupValues[2].let { if (it.startsWith("sec")) "second" else if (it.startsWith("min")) "minute" else "hour" }
    return LocalIntent(
        tool = "start_timer",
        args = mapOf("seconds" to seconds),
        summary = "Timer set for $n $unit${if (n == 1) "" else "s"}.",
    )
}

// ─── alarm ──────────────────────────────────────────────────────────────────

private val ALARM = Regex(
    """^(?:set |wake me (?:up )?)?(?:an )?(?:alarm )?(?:for |at )?(\d{1,2})(?::(\d{2}))? ?(am|pm)?$""",
)

private fun alarm(text: String): LocalIntent? {
    // Requires the word "alarm" somewhere: a bare "7:30" is far more likely
    // part of a sentence than a command.
    if (!text.contains("alarm") && !text.contains("wake me")) return null
    val m = ALARM.find(text) ?: return null

    var hour = m.groupValues[1].toIntOrNull() ?: return null
    val minute = m.groupValues[2].toIntOrNull() ?: 0
    val meridiem = m.groupValues[3]

    if (minute !in 0..59) return null
    when {
        meridiem == "pm" && hour < 12 -> hour += 12
        meridiem == "am" && hour == 12 -> hour = 0
    }
    if (hour !in 0..23) return null

    val shown = "${if (hour % 12 == 0) 12 else hour % 12}:${minute.toString().padStart(2, '0')} ${if (hour < 12) "am" else "pm"}"
    return LocalIntent(
        tool = "set_alarm",
        args = mapOf("hour" to hour, "minute" to minute),
        summary = "Alarm set for $shown.",
    )
}

// ─── volume ─────────────────────────────────────────────────────────────────

private val VOLUME_PCT = Regex("""^(?:set )?volume (?:to )?(\d{1,3}) ?%?$""")

private fun volume(text: String): LocalIntent? {
    VOLUME_PCT.find(text)?.let { m ->
        val pct = m.groupValues[1].toIntOrNull() ?: return null
        if (pct !in 0..100) return null
        return LocalIntent("set_volume", mapOf("percent" to pct), "Volume set to $pct%.")
    }
    return when (text) {
        "mute", "mute the volume", "volume off", "silence" ->
            LocalIntent("set_volume", mapOf("percent" to 0), "Muted.")
        "volume up", "turn it up", "turn the volume up", "louder" ->
            LocalIntent("set_volume", mapOf("relative" to 10), "Volume up.")
        "volume down", "turn it down", "turn the volume down", "quieter" ->
            LocalIntent("set_volume", mapOf("relative" to -10), "Volume down.")
        else -> null
    }
}

// ─── brightness ─────────────────────────────────────────────────────────────

private val BRIGHTNESS = Regex("""^(?:set )?brightness (?:to )?(\d{1,3}) ?%?$""")

private fun brightness(text: String): LocalIntent? {
    val m = BRIGHTNESS.find(text) ?: return null
    val pct = m.groupValues[1].toIntOrNull() ?: return null
    if (pct !in 0..100) return null
    return LocalIntent("set_brightness", mapOf("percent" to pct), "Brightness set to $pct%.")
}

// ─── media ──────────────────────────────────────────────────────────────────

private fun media(text: String): LocalIntent? = when (text) {
    "pause", "pause the music", "pause music", "stop the music" ->
        LocalIntent("media_control", mapOf("action" to "pause"), "Paused.")
    "play", "resume", "resume the music", "play music" ->
        LocalIntent("media_control", mapOf("action" to "play"), "Playing.")
    "next", "next song", "next track", "skip", "skip the song", "skip this song" ->
        LocalIntent("media_control", mapOf("action" to "next"), "Skipped.")
    "previous", "previous song", "previous track", "go back a song" ->
        LocalIntent("media_control", mapOf("action" to "previous"), "Previous track.")
    else -> null
}
