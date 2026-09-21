package org.tomsense.android.tools

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import kotlinx.serialization.json.put
import org.tomsense.tools.DeviceTool
import org.tomsense.tools.ToolCatalog
import org.tomsense.tools.errorJson
import org.tomsense.tools.okJson

/** Tools that read or change device state directly. */
internal fun systemTools(ctx: Context): List<DeviceTool> = listOf(

    Tool(ToolCatalog.SET_VOLUME) { args ->
        val percent = args.int("percent") ?: return@Tool errorJson("'percent' must be a number")
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (percent.coerceIn(0, 100) * max + 50) / 100
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        okJson { put("volume_percent", target * 100 / max) }
    },

    Tool(ToolCatalog.MEDIA_CONTROL) { args ->
        val action = args.require("action").lowercase()
        val key = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return@Tool errorJson("unknown action '$action'")
        }
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Both halves of the keypress: apps that track key state ignore a
        // lone DOWN and the transport control silently does nothing.
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        okJson { put("action", action) }
    },

    Tool(ToolCatalog.SET_BRIGHTNESS) { args ->
        val percent = args.int("percent") ?: return@Tool errorJson("'percent' must be a number")

        // WRITE_SETTINGS is a special access grant, not a runtime permission —
        // it cannot be requested with a dialog, only granted on a settings
        // screen. Opening that screen is the most useful thing we can do.
        if (!Settings.System.canWrite(ctx)) {
            ctx.launch(
                Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:${ctx.packageName}"),
                ),
            )
            return@Tool errorJson(
                "brightness needs the 'Modify system settings' permission — " +
                    "the settings screen is now open for the user to grant it",
            )
        }

        // Auto-brightness overrides a manual value within a second or two,
        // so turning it off is part of honouring the request rather than a
        // side effect.
        Settings.System.putInt(
            ctx.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        )
        val value = (percent.coerceIn(0, 100) * 255 + 50) / 100
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
        okJson { put("brightness_percent", percent.coerceIn(0, 100)) }
    },

    Tool(ToolCatalog.GET_DEVICE_STATUS) {
        val battery = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = battery.isCharging

        val connectivity =
            ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        val network = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }

        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volumePercent = audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 /
            audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

        okJson {
            put("battery_percent", level)
            put("charging", charging)
            put("network", network)
            put("media_volume_percent", volumePercent)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("android_version", Build.VERSION.RELEASE)
        }
    },
)
