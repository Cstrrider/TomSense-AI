package org.tomsense.android

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.service.quicksettings.TileService
import android.view.accessibility.AccessibilityEvent
import android.widget.RemoteViews

/**
 * The Android integration surface (spec §12).
 *
 * These are the entry points that make TomSense reachable the way Gemini is —
 * a tile, a widget, an intent, a notification listener. None of them are
 * expressible from a WebView on server.url, and collectively they are the
 * moat: things the big three won't do because they can't be this specific to
 * one person's setup.
 *
 * Everything invasive here (screen reading, notification access) is OPT-IN and
 * inert until the user grants it in system settings.
 */

/**
 * Foreground service hosting a duplex voice turn.
 *
 * Foreground is required, not cosmetic: a background service loses the
 * microphone the moment the screen turns off, which would cut the assistant
 * off mid-sentence every time the display timed out.
 */
class VoiceService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        // M5: open the WebSocket to /voice on the edge, stream mic PCM up,
        // play aura-2 audio down, and fire a barge message on local VAD.
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "Voice", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("TomSense listening")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    companion object {
        private const val CHANNEL = "voice"
        private const val NOTIF_ID = 1
    }
}

/** Quick Settings tile: one pull-down to start talking. */
class TomsenseTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, VoiceService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

/**
 * Notification triage (opt-in).
 *
 * Deliberately does NOT forward notification contents anywhere yet. Shipping
 * a listener that silently streams every notification to a server would be a
 * serious privacy change smuggled in as a feature; the filtering policy has
 * to be user-visible before anything leaves the device.
 */
class NotificationTriageService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // M7: apply the user's triage rules locally, surface only summaries.
    }
}

/**
 * Screen read and act.
 *
 * The most invasive permission on the platform, so it stays inert unless the
 * user has explicitly enabled the service AND asked a question that needs it.
 */
class ScreenReaderService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // M5: capture the active window's text on demand, never continuously.
    }

    override fun onInterrupt() = Unit
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
