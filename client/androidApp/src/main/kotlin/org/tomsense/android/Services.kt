package org.tomsense.android

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import android.widget.RemoteViews

/**
 * The Android integration surface (spec §12).
 *
 * These are the entry points that make TomSense reachable the way Gemini is —
 * a tile, a widget, an intent. None of them are expressible from a WebView on
 * server.url, and collectively they are the moat: things the big three won't
 * do because they can't be this specific to one person's setup.
 *
 * A NotificationListenerService and an AccessibilityService used to be
 * declared here with empty bodies. They are REMOVED rather than left pending,
 * because declaring them advertises the two most invasive permissions on the
 * platform, and a user who granted either would have handed over every
 * notification and every screen in exchange for a pair of no-op methods.
 * Screen text now comes from the assist API instead, which is granted
 * per-invocation at the moment the user asks — see assist/.
 */

/**
 * Quick Settings tile: one pull-down into a turn.
 *
 * It used to start a VoiceService that did nothing but post a notification —
 * a tile that looked functional and wasn't. Until the duplex voice path is
 * real (spec M5) this opens the app, which is at least honest.
 */
class TomsenseTileService : TileService() {
    override fun onClick() {
        super.onClick()
        // Required on recent Android: a tile may not launch an activity
        // directly, and doing so silently does nothing on the lock screen.
        val intent = Launch.intent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

/**
 * Intent endpoint so Tasker, shortcuts, or adb can drive the assistant:
 *
 *     adb shell am start -a org.tomsense.ASK --es text "what's on my calendar"
 */
class AskActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getStringExtra("text").orEmpty()
        if (text.isNotBlank()) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra("prefill", text)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        finish()
    }
}

/** Home-screen widget — a text box that drops you straight into a turn. */
class TomsenseWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_tomsense)
            val intent = Intent(context, MainActivity::class.java)
            val pending = android.app.PendingIntent.getActivity(
                context,
                0,
                intent,
                android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, pending)
            manager.updateAppWidget(id, views)
        }
    }
}

/** Send the user to the assistant-role picker. The role cannot be self-claimed. */
fun openAssistantRolePicker(context: Context) {
    context.startActivity(
        Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** Component name of our interaction service, for role checks. */
fun interactionServiceComponent(context: Context): ComponentName =
    ComponentName(context, org.tomsense.android.assist.TomsenseInteractionService::class.java)
