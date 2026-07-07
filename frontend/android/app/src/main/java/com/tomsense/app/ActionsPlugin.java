package com.tomsense.app;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.StatFs;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.KeyEvent;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Device-action bridge for the calendar-write / timer / alarm client tools.
 *
 * Each method fires a system Intent — the calendar's new-event editor (the
 * user reviews and saves), or the clock app's timer/alarm — so nothing is
 * written silently and no extra runtime permissions are needed beyond the
 * SET_ALARM normal permission. Registered by MainActivity.
 */
@CapacitorPlugin(name = "Actions")
public class ActionsPlugin extends Plugin {

    /** Current instance URL + whether it was user-set (vs the baked default).
     *  Backs the "Server" field in the web app's Settings. */
    @PluginMethod
    public void getServerUrl(PluginCall call) {
        JSObject o = new JSObject();
        o.put("url", ServerConfig.effective(getContext()));
        o.put("isSet", ServerConfig.stored(getContext()) != null);
        call.resolve(o);
    }

    /** Save a new instance URL and relaunch the WebView against it. */
    @PluginMethod
    public void setServerUrl(PluginCall call) {
        String url = ServerConfig.normalize(call.getString("url"));
        if (url == null) {
            call.reject("invalid url");
            return;
        }
        ServerConfig.save(getContext(), url);
        JSObject o = new JSObject();
        o.put("url", url);
        call.resolve(o);
        // Recreate AFTER resolving so the web side gets its answer first.
        getActivity().runOnUiThread(() -> getActivity().recreate());
    }

    /** Open the calendar's new-event editor, pre-filled. The user saves it. */
    @PluginMethod
    public void createCalendarEvent(PluginCall call) {
        Long startMs = call.getLong("startMs");
        if (startMs == null) {
            call.reject("startMs is required");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, call.getString("title", ""))
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, (long) startMs);
        Long endMs = call.getLong("endMs");
        if (endMs != null) {
            intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, (long) endMs);
        }
        String location = call.getString("location");
        if (location != null && !location.isEmpty()) {
            intent.putExtra(CalendarContract.Events.EVENT_LOCATION, location);
        }
        String notes = call.getString("notes");
        if (notes != null && !notes.isEmpty()) {
            intent.putExtra(CalendarContract.Events.DESCRIPTION, notes);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(intent);
        } catch (Exception e) {
            call.reject("No calendar app available: " + e.getMessage());
            return;
        }
        call.resolve(new JSObject().put("ok", true));
    }

    /** Start a countdown timer in the device clock app. */
    @PluginMethod
    public void startTimer(PluginCall call) {
        int seconds = call.getInt("seconds", 0);
        if (seconds < 1) {
            call.reject("seconds must be >= 1");
            return;
        }
        Intent intent = new Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        String label = call.getString("label");
        if (label != null && !label.isEmpty()) {
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(intent);
        } catch (Exception e) {
            call.reject("No clock app available: " + e.getMessage());
            return;
        }
        call.resolve(new JSObject().put("ok", true));
    }

    /** Drain a pending "Share to TomSense" payload (text or image), if any. */
    @PluginMethod
    public void consumeSharedContent(PluginCall call) {
        call.resolve(MainActivity.takeShared());
    }

    // ── Common app name → package name lookup ────────────────────────────────

    // Package-private so AssistActivity.NativeBridge can reuse the same map.
    static final Map<String, String> COMMON_PACKAGES = new HashMap<>();
    static {
        COMMON_PACKAGES.put("spotify",         "com.spotify.music");
        COMMON_PACKAGES.put("youtube",         "com.google.android.youtube");
        COMMON_PACKAGES.put("gmail",           "com.google.android.gm");
        COMMON_PACKAGES.put("google maps",     "com.google.android.apps.maps");
        COMMON_PACKAGES.put("maps",            "com.google.android.apps.maps");
        COMMON_PACKAGES.put("instagram",       "com.instagram.android");
        COMMON_PACKAGES.put("whatsapp",        "com.whatsapp");
        COMMON_PACKAGES.put("facebook",        "com.facebook.katana");
        COMMON_PACKAGES.put("twitter",         "com.twitter.android");
        COMMON_PACKAGES.put("x",               "com.twitter.android");
        COMMON_PACKAGES.put("netflix",         "com.netflix.mediaclient");
        COMMON_PACKAGES.put("chrome",          "com.android.chrome");
        COMMON_PACKAGES.put("photos",          "com.google.android.apps.photos");
        COMMON_PACKAGES.put("google photos",   "com.google.android.apps.photos");
        COMMON_PACKAGES.put("clock",           "com.google.android.deskclock");
        COMMON_PACKAGES.put("calculator",      "com.google.android.calculator");
        COMMON_PACKAGES.put("messages",        "com.google.android.apps.messaging");
        COMMON_PACKAGES.put("phone",           "com.google.android.dialer");
        COMMON_PACKAGES.put("settings",        "com.android.settings");
        COMMON_PACKAGES.put("tiktok",          "com.zhiliaoapp.musically");
        COMMON_PACKAGES.put("snapchat",        "com.snapchat.android");
        COMMON_PACKAGES.put("amazon",          "com.amazon.mShop.android.shopping");
        COMMON_PACKAGES.put("uber",            "com.ubercab");
        COMMON_PACKAGES.put("lyft",            "me.lyft.android");
        COMMON_PACKAGES.put("discord",         "com.discord");
        COMMON_PACKAGES.put("slack",           "com.Slack");
        COMMON_PACKAGES.put("zoom",            "us.zoom.videomeetings");
        COMMON_PACKAGES.put("reddit",          "com.reddit.frontpage");
        COMMON_PACKAGES.put("linkedin",        "com.linkedin.android");
        COMMON_PACKAGES.put("outlook",         "com.microsoft.office.outlook");
        COMMON_PACKAGES.put("teams",           "com.microsoft.teams");
        COMMON_PACKAGES.put("google",          "com.google.android.googlequicksearchbox");
        COMMON_PACKAGES.put("google assistant","com.google.android.googlequicksearchbox");
        COMMON_PACKAGES.put("files",           "com.google.android.apps.nbu.files");
        COMMON_PACKAGES.put("drive",           "com.google.android.apps.docs");
        COMMON_PACKAGES.put("google drive",    "com.google.android.apps.docs");
        COMMON_PACKAGES.put("docs",            "com.google.android.apps.docs.editors.docs");
        COMMON_PACKAGES.put("sheets",          "com.google.android.apps.docs.editors.sheets");
        COMMON_PACKAGES.put("slides",          "com.google.android.apps.docs.editors.slides");
        COMMON_PACKAGES.put("meet",            "com.google.android.apps.meetings");
        COMMON_PACKAGES.put("google meet",     "com.google.android.apps.meetings");
        COMMON_PACKAGES.put("play store",      "com.android.vending");
        COMMON_PACKAGES.put("play",            "com.android.vending");
        COMMON_PACKAGES.put("gmail",           "com.google.android.gm");
        COMMON_PACKAGES.put("keep",            "com.google.android.keep");
        COMMON_PACKAGES.put("google keep",     "com.google.android.keep");
    }

    /** Open an installed app by common name or package name. */
    @PluginMethod
    public void launchApp(PluginCall call) {
        String appName = call.getString("appName");
        if (appName == null || appName.isEmpty()) {
            call.reject("appName is required");
            return;
        }

        PackageManager pm = getContext().getPackageManager();
        String lower = appName.toLowerCase(Locale.US).trim();

        // 1. Common-name map
        String pkg = COMMON_PACKAGES.get(lower);

        // 2. Search all launcher apps by label
        if (pkg == null) {
            Intent mainIntent = new Intent(Intent.ACTION_MAIN);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> apps = pm.queryIntentActivities(mainIntent, 0);
            for (ResolveInfo info : apps) {
                String label = info.loadLabel(pm).toString().toLowerCase(Locale.US);
                if (label.equals(lower) || label.contains(lower)) {
                    pkg = info.activityInfo.packageName;
                    break;
                }
            }
        }

        if (pkg == null) {
            call.reject("App not found: " + appName);
            return;
        }

        Intent launchIntent = pm.getLaunchIntentForPackage(pkg);
        if (launchIntent == null) {
            call.reject("No launch intent for: " + appName);
            return;
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(launchIntent);
            call.resolve(new JSObject().put("ok", true));
        } catch (Exception e) {
            call.reject("Couldn't launch " + appName + ": " + e.getMessage());
        }
    }

    /** Open the phone dialer pre-filled with a number (user must tap Call). */
    @PluginMethod
    public void makeCall(PluginCall call) {
        String number = call.getString("phoneNumber");
        if (number == null || number.isEmpty()) {
            call.reject("phoneNumber is required");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(intent);
            call.resolve(new JSObject().put("ok", true));
        } catch (Exception e) {
            call.reject("No phone app available: " + e.getMessage());
        }
    }

    /** Open the SMS composer pre-filled with a recipient and optional message. */
    @PluginMethod
    public void sendSms(PluginCall call) {
        String number = call.getString("phoneNumber");
        if (number == null || number.isEmpty()) {
            call.reject("phoneNumber is required");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)));
        String message = call.getString("message");
        if (message != null && !message.isEmpty()) {
            intent.putExtra("sms_body", message);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(intent);
            call.resolve(new JSObject().put("ok", true));
        } catch (Exception e) {
            call.reject("No messaging app available: " + e.getMessage());
        }
    }

    /** Open maps/navigation to a destination. */
    @PluginMethod
    public void openMaps(PluginCall call) {
        String destination = call.getString("destination");
        if (destination == null || destination.isEmpty()) {
            call.reject("destination is required");
            return;
        }
        String mode = call.getString("mode", "");

        // Build a geo: URI for universal maps compatibility
        Uri geoUri = Uri.parse("geo:0,0?q=" + Uri.encode(destination));
        Intent intent = new Intent(Intent.ACTION_VIEW, geoUri);

        // If a mode is requested try Google Maps navigation URI first
        if (mode != null && !mode.isEmpty()) {
            String gmMode = mode.equals("transit") ? "r"
                          : mode.equals("walking") ? "w"
                          : mode.equals("cycling") ? "b"
                          : "d"; // driving default
            Uri navUri = Uri.parse(
                "google.navigation:q=" + Uri.encode(destination) + "&mode=" + gmMode);
            Intent navIntent = new Intent(Intent.ACTION_VIEW, navUri);
            navIntent.setPackage("com.google.android.apps.maps");
            navIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                getContext().startActivity(navIntent);
                call.resolve(new JSObject().put("ok", true));
                return;
            } catch (Exception ignored) {
                // Google Maps not installed — fall through to geo: URI
            }
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(intent);
            call.resolve(new JSObject().put("ok", true));
        } catch (Exception e) {
            call.reject("No maps app available: " + e.getMessage());
        }
    }

    /** Open a URL in the default browser. */
    @PluginMethod
    public void openUrl(PluginCall call) {
        String url = call.getString("url");
        if (url == null || url.isEmpty()) {
            call.reject("url is required");
            return;
        }
        // Only prepend https:// for bare hostnames — pass app URI schemes (spotify:, geo:, etc.) through as-is.
        if (!url.matches("^[a-z][a-z0-9+\\-.]*:.*")) {
            url = "https://" + url;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(intent);
            call.resolve(new JSObject().put("ok", true));
        } catch (Exception e) {
            call.reject("Couldn't open URL: " + e.getMessage());
        }
    }

    /** Show the Android share sheet for a piece of text. */
    @PluginMethod
    public void shareText(PluginCall call) {
        String text = call.getString("text");
        if (text == null || text.isEmpty()) {
            call.reject("text is required");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, text);
        String title = call.getString("title");
        if (title != null && !title.isEmpty()) {
            intent.putExtra(Intent.EXTRA_SUBJECT, title);
        }
        Intent chooser = Intent.createChooser(intent, "Share via");
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(chooser);
            call.resolve(new JSObject().put("ok", true));
        } catch (Exception e) {
            call.reject("Couldn't open share sheet: " + e.getMessage());
        }
    }

    static final Map<String, String> SETTINGS_ACTIONS = new HashMap<>();
    static {
        SETTINGS_ACTIONS.put("wifi",           Settings.ACTION_WIFI_SETTINGS);
        SETTINGS_ACTIONS.put("bluetooth",      Settings.ACTION_BLUETOOTH_SETTINGS);
        SETTINGS_ACTIONS.put("location",       Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        SETTINGS_ACTIONS.put("notifications",  "android.settings.NOTIFICATION_SETTINGS");
        SETTINGS_ACTIONS.put("sound",          Settings.ACTION_SOUND_SETTINGS);
        SETTINGS_ACTIONS.put("display",        Settings.ACTION_DISPLAY_SETTINGS);
        SETTINGS_ACTIONS.put("battery",        Settings.ACTION_BATTERY_SAVER_SETTINGS);
        SETTINGS_ACTIONS.put("apps",           Settings.ACTION_APPLICATION_SETTINGS);
        SETTINGS_ACTIONS.put("storage",        Settings.ACTION_INTERNAL_STORAGE_SETTINGS);
        SETTINGS_ACTIONS.put("security",       Settings.ACTION_SECURITY_SETTINGS);
        SETTINGS_ACTIONS.put("accessibility",  Settings.ACTION_ACCESSIBILITY_SETTINGS);
    }

    /** Open a specific settings panel, or the main Settings app. */
    @PluginMethod
    public void openSettings(PluginCall call) {
        String panel = call.getString("panel", "");
        String action = (panel != null && !panel.isEmpty())
            ? SETTINGS_ACTIONS.getOrDefault(panel.toLowerCase(Locale.US), Settings.ACTION_SETTINGS)
            : Settings.ACTION_SETTINGS;
        Intent intent = new Intent(action);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(intent);
            call.resolve(new JSObject().put("ok", true));
        } catch (Exception e) {
            // Fallback to main settings if a specific panel isn't available
            try {
                getContext().startActivity(
                    new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                call.resolve(new JSObject().put("ok", true));
            } catch (Exception e2) {
                call.reject("Couldn't open settings: " + e2.getMessage());
            }
        }
    }

    /** Set an alarm in the device clock app. */
    @PluginMethod
    public void setAlarm(PluginCall call) {
        int hour = call.getInt("hour", -1);
        int minute = call.getInt("minute", 0);
        if (hour < 0 || hour > 23) {
            call.reject("hour must be 0-23");
            return;
        }
        Intent intent = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        String label = call.getString("label");
        if (label != null && !label.isEmpty()) {
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(intent);
        } catch (Exception e) {
            call.reject("No clock app available: " + e.getMessage());
            return;
        }
        call.resolve(new JSObject().put("ok", true));
    }

    // ── Volume / brightness / media / status / music ─────────────────────────
    // Shared static helpers (Context-based) so AssistActivity.NativeBridge can
    // run the same logic from the overlay's plain WebView. Each returns a
    // model-readable success string, or "error: <message>".

    static String doSetVolume(Context ctx, int level, String action) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return "error: audio service unavailable";
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        try {
            if (level >= 0) {
                int v = Math.round(max * Math.min(100, level) / 100f);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, v, AudioManager.FLAG_SHOW_UI);
            } else if ("mute".equals(action)) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI);
            } else if ("unmute".equals(action)) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI);
            } else if ("up".equals(action) || "down".equals(action)) {
                // One spoken "turn it up" ≈ a couple of rocker presses.
                int delta = Math.max(1, Math.round(max / 6f));
                int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                int v = "up".equals(action)
                        ? Math.min(max, cur + delta) : Math.max(0, cur - delta);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, v, AudioManager.FLAG_SHOW_UI);
            } else {
                return "error: give a level (0-100) or an action (up/down/mute/unmute)";
            }
        } catch (SecurityException e) {
            // Muting can be blocked while Do Not Disturb is on.
            return "error: blocked by Do Not Disturb — " + e.getMessage();
        }
        int nowPct = Math.round(100f * am.getStreamVolume(AudioManager.STREAM_MUSIC) / max);
        return "Media volume is now " + nowPct + "%.";
    }

    static String doSetBrightness(Context ctx, int level) {
        if (!Settings.System.canWrite(ctx)) {
            // One-time special grant: open the toggle screen for this app.
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:" + ctx.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception ignored) { }
            return "error: TomSense needs the 'Modify system settings' permission. "
                 + "The grant screen was just opened — ask the user to enable it "
                 + "for TomSense, then ask again.";
        }
        int pct = Math.max(0, Math.min(100, level));
        int v = Math.max(1, Math.round(255 * pct / 100f));
        try {
            Settings.System.putInt(ctx.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(ctx.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, v);
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
        return "Screen brightness set to " + pct + "% (adaptive brightness turned off).";
    }

    static String doMediaControl(Context ctx, String action) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return "error: audio service unavailable";
        int key;
        switch (action == null ? "" : action) {
            case "play":     key = KeyEvent.KEYCODE_MEDIA_PLAY; break;
            case "pause":    key = KeyEvent.KEYCODE_MEDIA_PAUSE; break;
            case "toggle":   key = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; break;
            case "next":     key = KeyEvent.KEYCODE_MEDIA_NEXT; break;
            case "previous": key = KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
            case "stop":     key = KeyEvent.KEYCODE_MEDIA_STOP; break;
            default: return "error: unknown action '" + action + "'";
        }
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
        return "Sent '" + action + "' to the active media player. If nothing was "
             + "playing there may be no player to respond.";
    }

    static String doGetDeviceStatus(Context ctx) {
        StringBuilder sb = new StringBuilder("Device status: ");
        try {
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            int pct = bm != null
                    ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
            Intent bi = ctx.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int st = bi != null ? bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1) : -1;
            boolean charging = st == BatteryManager.BATTERY_STATUS_CHARGING
                            || st == BatteryManager.BATTERY_STATUS_FULL;
            sb.append("battery ").append(pct >= 0 ? pct + "%" : "unknown")
              .append(charging ? " (charging)" : " (not charging)");
        } catch (Exception e) {
            sb.append("battery unknown");
        }
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities caps = n != null ? cm.getNetworkCapabilities(n) : null;
            String net = caps == null ? "offline"
                    : caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "WiFi"
                    : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "mobile data"
                    : "connected";
            sb.append("; network ").append(net);
        } catch (Exception ignored) { }
        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            double freeGb = fs.getAvailableBytes() / 1e9;
            double totalGb = fs.getTotalBytes() / 1e9;
            sb.append(String.format(Locale.US,
                    "; storage %.1f GB free of %.0f GB", freeGb, totalGb));
        } catch (Exception ignored) { }
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                sb.append("; media volume ")
                  .append(Math.round(100f * am.getStreamVolume(AudioManager.STREAM_MUSIC) / max))
                  .append("%");
            }
        } catch (Exception ignored) { }
        try {
            int b = Settings.System.getInt(ctx.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, -1);
            if (b >= 0) sb.append("; brightness ").append(Math.round(100f * b / 255)).append("%");
        } catch (Exception ignored) { }
        return sb.append(".").toString();
    }

    static String doPlayMusic(Context ctx, String query, String kind, String app) {
        if (query == null || query.isEmpty()) return "error: no query";
        String pkg = (app == null || app.isEmpty() || "spotify".equals(app))
                ? "com.spotify.music"
                : "youtube music".equals(app) ? "com.google.android.apps.youtube.music"
                : null; // "any" — let Android pick a handler
        Intent i = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH);
        i.putExtra(SearchManager.QUERY, query);
        String k = kind == null ? "" : kind;
        // EXTRA_MEDIA_FOCUS tells the player what the query names, so it can
        // start the right thing playing instead of showing search results.
        switch (k) {
            case "song":
                i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio");
                i.putExtra(MediaStore.EXTRA_MEDIA_TITLE, query);
                break;
            case "artist":
                i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/artist");
                i.putExtra(MediaStore.EXTRA_MEDIA_ARTIST, query);
                break;
            case "album":
                i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/album");
                i.putExtra(MediaStore.EXTRA_MEDIA_ALBUM, query);
                break;
            case "playlist":
                i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/playlist");
                i.putExtra("android.intent.extra.playlist", query);
                break;
            default:
                break; // unstructured search — the player decides
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (pkg != null) {
            i.setPackage(pkg);
            try {
                ctx.startActivity(i);
                return "Started playing \"" + query + "\" via "
                     + ("com.spotify.music".equals(pkg) ? "Spotify" : "YouTube Music") + ".";
            } catch (Exception e) {
                i.setPackage(null); // app missing — fall through to any handler
            }
        }
        try {
            ctx.startActivity(i);
            return "Started playing \"" + query + "\" in the default music app.";
        } catch (Exception e) {
            return "error: no music app could handle the request — " + e.getMessage();
        }
    }

    /** Set the media volume — absolute `level` (0-100) or `action` up/down/mute/unmute. */
    @PluginMethod
    public void setVolume(PluginCall call) {
        String r = doSetVolume(getContext(),
                call.getInt("level", -1), call.getString("action", ""));
        if (r.startsWith("error:")) call.reject(r.substring(6).trim());
        else call.resolve(new JSObject().put("ok", true).put("detail", r));
    }

    /** Set the screen brightness (0-100). Needs the WRITE_SETTINGS special grant. */
    @PluginMethod
    public void setBrightness(PluginCall call) {
        String r = doSetBrightness(getContext(), call.getInt("level", 50));
        if (r.startsWith("error:")) call.reject(r.substring(6).trim());
        else call.resolve(new JSObject().put("ok", true).put("detail", r));
    }

    /** Send a media-key command to whatever is playing. */
    @PluginMethod
    public void mediaControl(PluginCall call) {
        String r = doMediaControl(getContext(), call.getString("action", ""));
        if (r.startsWith("error:")) call.reject(r.substring(6).trim());
        else call.resolve(new JSObject().put("ok", true).put("detail", r));
    }

    /** Battery / network / storage / volume / brightness one-liner. */
    @PluginMethod
    public void getDeviceStatus(PluginCall call) {
        String r = doGetDeviceStatus(getContext());
        if (r.startsWith("error:")) call.reject(r.substring(6).trim());
        else call.resolve(new JSObject().put("ok", true).put("detail", r));
    }

    /** Save a base64 image into the device photo library (Pictures/TomSense).
     *  MediaStore + RELATIVE_PATH needs API 29+; no storage permission
     *  required for app-created media. */
    @PluginMethod
    public void saveImage(PluginCall call) {
        String b64 = call.getString("base64");
        if (b64 == null || b64.isEmpty()) {
            call.reject("base64 is required");
            return;
        }
        if (android.os.Build.VERSION.SDK_INT < 29) {
            call.reject("Saving to Photos requires Android 10+");
            return;
        }
        String mime = call.getString("mime", "image/png");
        String filename = call.getString("filename",
                "tomsense-" + System.currentTimeMillis() + ".png");
        try {
            byte[] bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
            v.put(MediaStore.Images.Media.MIME_TYPE, mime);
            v.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/TomSense");
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            android.content.ContentResolver cr = getContext().getContentResolver();
            Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) {
                call.reject("MediaStore insert failed");
                return;
            }
            try (java.io.OutputStream os = cr.openOutputStream(uri)) {
                if (os == null) throw new java.io.IOException("no output stream");
                os.write(bytes);
            }
            v.clear();
            v.put(MediaStore.Images.Media.IS_PENDING, 0);
            cr.update(uri, v, null, null);
            call.resolve(new JSObject().put("ok", true)
                    .put("detail", "Saved to Photos (Pictures/TomSense)."));
        } catch (Exception e) {
            call.reject("Couldn't save image: " + e.getMessage());
        }
    }

    /** Start music playing (Spotify by default) via MEDIA_PLAY_FROM_SEARCH. */
    @PluginMethod
    public void playMusic(PluginCall call) {
        String r = doPlayMusic(getContext(), call.getString("query", ""),
                call.getString("kind", ""), call.getString("app", ""));
        if (r.startsWith("error:")) call.reject(r.substring(6).trim());
        else call.resolve(new JSObject().put("ok", true).put("detail", r));
    }
}
