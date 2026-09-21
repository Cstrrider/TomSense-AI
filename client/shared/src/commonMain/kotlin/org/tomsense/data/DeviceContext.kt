package org.tomsense.data

/**
 * Local date, time and zone, e.g. "Sunday 21 September 2026, 09:15 (America/Los_Angeles)".
 *
 * The device's own clock, not the edge's. A Worker runs in whatever region
 * took the request, so its idea of "today" is UTC at best and wrong by a day
 * at worst — for someone in Los Angeles it is tomorrow from late afternoon on.
 */
expect fun deviceClock(): String

/**
 * The system turn prepended to every generation.
 *
 * Exists because of an observed failure, not a guess: asked to "remind me to
 * call the dentist at 3pm today", the model called `get_calendar`, then
 * `get_calendar` again, then `get_device_status` — burning three paid rounds
 * probing tools to work out what day it was, and never setting the reminder.
 * With the clock in context it calls `set_reminder` with the right ISO
 * timestamp on the first round.
 *
 * Anything time-relative — "tonight", "tomorrow", "in 20 minutes" — depends on
 * this, so it is sent on every request rather than only when tools are in play.
 */
fun deviceSystemPrompt(): String = buildString {
    appendLine("You are TomSense, the user's assistant, running on their own device.")
    appendLine()
    appendLine("Current local time: ${deviceClock()}.")
    appendLine(
        "Resolve relative times such as \"tonight\", \"tomorrow\" or \"in 20 minutes\" " +
            "against that — it is the only clock you have.",
    )
    appendLine()
    append(
        "Device tools act on this device directly and immediately. When the user " +
            "asks for something a tool does, use the tool rather than explaining " +
            "how they could do it themselves.",
    )
}
