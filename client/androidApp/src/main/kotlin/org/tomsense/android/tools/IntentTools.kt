package org.tomsense.android.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import org.tomsense.tools.DeviceTool
import org.tomsense.tools.ToolCatalog
import org.tomsense.tools.ToolSpec
import org.tomsense.tools.errorJson
import org.tomsense.tools.okJson

/** Terse [DeviceTool] binding a catalog entry to the code that runs it. */
internal class Tool(
    override val spec: ToolSpec,
    private val run: suspend (JsonObject) -> String,
) : DeviceTool {
    override suspend fun execute(args: JsonObject): String = run(args)
}

/**
 * Tools that hand off to another app.
 *
 * All of these are safe to expose without a confirmation step of our own,
 * because the app they open IS the confirmation: the dialer shows the number
 * before it rings, the SMS app shows the message before it sends, the alarm
 * app shows the time before it is set.
 */
internal fun intentTools(ctx: Context): List<DeviceTool> = listOf(

    Tool(ToolCatalog.OPEN_URL) { args ->
        val url = args.require("url")
        // A bare host would resolve as a file path and open nothing.
        val normalized = if (url.startsWith("http")) url else "https://$url"
        if (ctx.launch(Intent(Intent.ACTION_VIEW, Uri.parse(normalized)))) {
            okJson { put("opened", normalized) }
        } else {
            errorJson("no browser available")
        }
    },

    Tool(ToolCatalog.OPEN_MAPS) { args ->
        val query = args.require("query")
        val navigating = args.bool("navigate") == true
        val uri = if (navigating) {
            Uri.parse("google.navigation:q=${Uri.encode(query)}")
        } else {
            Uri.parse("geo:0,0?q=${Uri.encode(query)}")
        }
        if (ctx.launch(Intent(Intent.ACTION_VIEW, uri))) {
            okJson { put("query", query); put("navigating", navigating) }
        } else {
            errorJson("no maps app available")
        }
    },

    Tool(ToolCatalog.SHARE_TEXT) { args ->
        val text = args.require("text")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            args.str("subject")?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        if (ctx.launch(Intent.createChooser(intent, "Share"))) {
            okJson { put("shared_chars", text.length) }
        } else {
            errorJson("share sheet unavailable")
        }
    },

    Tool(ToolCatalog.MAKE_CALL) { args ->
        val number = args.require("number")
        // ACTION_DIAL, never ACTION_CALL: see the class comment on AndroidToolset.
        if (ctx.launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")))) {
            okJson { put("dialer_opened_with", number) }
        } else {
            errorJson("no dialer available")
        }
    },

    Tool(ToolCatalog.SEND_SMS) { args ->
        val number = args.require("number")
        val message = args.require("message")
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}"))
            .putExtra("sms_body", message)
        if (ctx.launch(intent)) {
            okJson { put("composed_to", number) }
        } else {
            errorJson("no messaging app available")
        }
    },

    Tool(ToolCatalog.LAUNCH_APP) { args ->
        val wanted = args.require("app")
        val match = findLaunchable(ctx, wanted)
            ?: return@Tool errorJson("no installed app matching '$wanted'")
        val intent = ctx.packageManager.getLaunchIntentForPackage(match.second)
            ?: return@Tool errorJson("${match.first} cannot be launched")
        if (ctx.launch(intent)) okJson { put("launched", match.first) }
        else errorJson("could not launch ${match.first}")
    },

    Tool(ToolCatalog.START_TIMER) { args ->
        val seconds = args.int("seconds") ?: return@Tool errorJson("'seconds' must be a number")
        if (seconds <= 0) return@Tool errorJson("'seconds' must be positive")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            args.str("label")?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        }
        if (ctx.launch(intent)) okJson { put("timer_seconds", seconds) }
        else errorJson("no clock app available")
    },

    Tool(ToolCatalog.SET_ALARM) { args ->
        val hour = args.int("hour") ?: return@Tool errorJson("'hour' must be a number")
        val minute = args.int("minute") ?: 0
        if (hour !in 0..23 || minute !in 0..59) {
            return@Tool errorJson("hour must be 0-23 and minute 0-59")
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            args.str("label")?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            // Deliberately NOT skipping the UI: an alarm silently set for the
            // wrong day is the kind of failure you discover at 3am.
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        if (ctx.launch(intent)) okJson { put("hour", hour); put("minute", minute) }
        else errorJson("no clock app available")
    },

    Tool(ToolCatalog.PLAY_MUSIC) { args ->
        val query = args.require("query")
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(android.app.SearchManager.QUERY, query)
        }
        if (ctx.launch(intent)) okJson { put("playing", query) }
        else errorJson("no music app handles play-from-search")
    },

    Tool(ToolCatalog.OPEN_SETTINGS) { args ->
        val screen = args.require("screen")
        val action = SETTINGS_ACTIONS[screen]
            ?: return@Tool errorJson(
                "unknown screen '$screen'; valid: ${SETTINGS_ACTIONS.keys.joinToString()}",
            )
        if (ctx.launch(Intent(action))) okJson { put("opened", screen) }
        else errorJson("could not open $screen settings")
    },
)

/**
 * Screen name to Intent action.
 *
 * The keys MUST match `SETTINGS_SCREENS` in the shared catalog, which is the
 * enum the model is shown; a key here with no entry there is a screen nothing
 * can ask for, and the reverse is a tool call that always fails.
 */
private val SETTINGS_ACTIONS: Map<String, String> = mapOf(
    "wifi" to Settings.ACTION_WIFI_SETTINGS,
    "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
    "display" to Settings.ACTION_DISPLAY_SETTINGS,
    "sound" to Settings.ACTION_SOUND_SETTINGS,
    "battery" to Settings.ACTION_BATTERY_SAVER_SETTINGS,
    "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
    "apps" to Settings.ACTION_APPLICATION_SETTINGS,
    "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
    "airplane" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
    "date" to Settings.ACTION_DATE_SETTINGS,
    "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
    "notifications" to Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
)

/**
 * Resolve a spoken app name to a package.
 *
 * Exact match first, then prefix, then substring — "play" should not win
 * "Google Play Store" over "Play Music" if the user said the latter, and
 * ranking by match quality is what keeps the common case right.
 */
private fun findLaunchable(ctx: Context, wanted: String): Pair<String, String>? {
    val target = wanted.trim().lowercase()
    val pm = ctx.packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    val candidates = pm.queryIntentActivities(launcherIntent, 0).mapNotNull { info ->
        val label = info.loadLabel(pm)?.toString() ?: return@mapNotNull null
        label to info.activityInfo.packageName
    }

    return candidates.firstOrNull { it.first.lowercase() == target }
        ?: candidates.firstOrNull { it.first.lowercase().startsWith(target) }
        ?: candidates.firstOrNull { it.first.lowercase().contains(target) }
}
