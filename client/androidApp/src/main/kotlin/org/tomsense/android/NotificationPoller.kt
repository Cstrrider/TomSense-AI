package org.tomsense.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Notifications for things that happened while the app was closed — so far,
 * scheduled prompts finishing.
 *
 * POLLED, not pushed. Push means FCM, and FCM means a Firebase project and a
 * google-services.json baked into the build — an account dependency this app
 * otherwise doesn't have, and one every self-hoster would have to recreate.
 * Schedules already run on a 15-minute cron, so a 15-minute poll (WorkManager's
 * minimum period, batched by the OS with other work) adds no meaningful delay.
 */
class NotificationPoller(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as TomsenseApp
        val items = runCatching { app.features.notifications() }.getOrElse { return Result.retry() }
        if (items.isEmpty()) return Result.success()

        ensureChannel(applicationContext)
        val allowed = Build.VERSION.SDK_INT < 33 ||
            applicationContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!allowed) return Result.success()

        val nm = NotificationManagerCompat.from(applicationContext)
        for (n in items) {
            val open = PendingIntent.getActivity(
                applicationContext,
                n.id.hashCode(),
                Launch.intent(applicationContext, conversationId = n.convId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(n.title)
                .setContentText(n.body.lineSequence().firstOrNull().orEmpty())
                .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            runCatching { nm.notify(n.id.hashCode(), notification) }
        }
        return Result.success()
    }

    companion object {
        private const val CHANNEL = "scheduled"
        private const val WORK = "tomsense-notifications"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<NotificationPoller>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // KEEP: re-scheduling on every resume must not reset the period.
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Scheduled results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "Results from prompts you scheduled in Settings → Schedules"
                    },
                )
            }
        }
    }
}
