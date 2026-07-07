package com.tomsense.app;

import android.Manifest;
import android.content.ContentResolver;
import android.database.Cursor;
import android.provider.CalendarContract;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/**
 * Read-only calendar bridge for the `get_calendar` client tool.
 *
 * Registered by MainActivity (a local plugin isn't auto-discovered). Lists
 * event instances in an upcoming window via CalendarContract.Instances —
 * which expands recurring events for the range, unlike a raw Events query.
 */
@CapacitorPlugin(
    name = "Calendar",
    permissions = {
        @Permission(strings = { Manifest.permission.READ_CALENDAR }, alias = "calendar")
    }
)
public class CalendarPlugin extends Plugin {

    private static final String[] PROJECTION = {
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END,
        CalendarContract.Instances.EVENT_LOCATION,
        CalendarContract.Instances.ALL_DAY,
        CalendarContract.Instances.CALENDAR_DISPLAY_NAME
    };

    @PluginMethod
    public void getEvents(PluginCall call) {
        if (getPermissionState("calendar") == PermissionState.GRANTED) {
            loadEvents(call);
        } else {
            requestPermissionForAlias("calendar", call, "calendarPermCallback");
        }
    }

    @PermissionCallback
    private void calendarPermCallback(PluginCall call) {
        if (getPermissionState("calendar") == PermissionState.GRANTED) {
            loadEvents(call);
        } else {
            call.reject("Calendar permission was denied");
        }
    }

    private void loadEvents(PluginCall call) {
        int days = call.getInt("days", 7);
        if (days < 1) days = 1;
        if (days > 30) days = 30;

        long begin = System.currentTimeMillis();
        long end = begin + (long) days * 24L * 60L * 60L * 1000L;

        JSArray events = new JSArray();
        try {
            ContentResolver cr = getContext().getContentResolver();
            Cursor c = CalendarContract.Instances.query(cr, PROJECTION, begin, end);
            if (c != null) {
                try {
                    int count = 0;
                    while (c.moveToNext() && count < 100) {
                        JSObject ev = new JSObject();
                        ev.put("title", c.isNull(0) ? "(untitled)" : c.getString(0));
                        ev.put("begin", c.getLong(1));
                        ev.put("end", c.getLong(2));
                        ev.put("location", c.isNull(3) ? "" : c.getString(3));
                        ev.put("allDay", c.getInt(4) == 1);
                        ev.put("calendar", c.isNull(5) ? "" : c.getString(5));
                        events.put(ev);
                        count++;
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Exception e) {
            call.reject("Calendar query failed: " + e.getMessage());
            return;
        }

        JSObject ret = new JSObject();
        ret.put("events", events);
        call.resolve(ret);
    }
}
