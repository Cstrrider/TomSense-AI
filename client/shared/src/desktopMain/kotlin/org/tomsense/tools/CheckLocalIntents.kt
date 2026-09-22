package org.tomsense.tools

/**
 * Exercise the tier-0 matcher without a device.
 *
 * `./gradlew -q :shared:checkLocalIntents`
 *
 * The NEGATIVE cases are the point. A miss costs a round trip; a false
 * positive hijacks a message the user meant as conversation and answers it
 * with a timer, which is a far worse failure and an easy one to introduce
 * while making the patterns more generous.
 */
fun main() {
    val shouldMatch = listOf(
        "set a timer for 10 minutes" to "start_timer",
        "timer for 5 min" to "start_timer",
        "start a timer for two hours" to "start_timer",
        "Set a timer for 30 seconds." to "start_timer",
        "set an alarm for 7:30 am" to "set_alarm",
        "alarm at 6" to "set_alarm",
        "wake me up at 6:15am" to "set_alarm",
        "volume to 40%" to "set_volume",
        "mute" to "set_volume",
        "turn it up" to "set_volume",
        "brightness to 80" to "set_brightness",
        "next song" to "media_control",
        "pause" to "media_control",
        "skip" to "media_control",
    )

    val shouldNotMatch = listOf(
        "how do I set a timer on my oven",
        "what's a good timer app",
        "can you explain how alarm clocks work",
        "the alarm went off at 3am and I couldn't sleep, any advice",
        "write me a poem about volume",
        "why is the brightness of a star measured in magnitudes",
        "next, explain the second point",
        "play devil's advocate for me",
        "skip the pleasantries and tell me what you think",
        "set a timer for as long as it takes to write this essay",
        "pause and consider the implications",
        "what does mute mean in music notation",
        "turn it up to eleven, metaphorically speaking",
        "",
        "timer",
    )

    var failures = 0

    println("── should match ──")
    for ((text, tool) in shouldMatch) {
        val hit = matchLocalIntent(text)
        val ok = hit?.tool == tool
        if (!ok) failures++
        println("${if (ok) "  ok  " else "  FAIL"} \"$text\" -> ${hit?.tool ?: "no match"}${hit?.let { " " + it.args } ?: ""}")
    }

    println()
    println("── must NOT match (false positives are the dangerous failure) ──")
    for (text in shouldNotMatch) {
        val hit = matchLocalIntent(text)
        val ok = hit == null
        if (!ok) failures++
        println("${if (ok) "  ok  " else "  FAIL"} \"$text\"${hit?.let { " -> HIJACKED by " + it.tool } ?: ""}")
    }

    println()
    println(if (failures == 0) "all clear" else "$failures failure(s)")
    if (failures > 0) kotlin.system.exitProcess(1)
}
