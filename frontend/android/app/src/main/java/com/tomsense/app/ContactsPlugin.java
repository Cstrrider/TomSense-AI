package com.tomsense.app;

import android.Manifest;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;

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
 * Capacitor plugin exposing Android Contacts to the get_contacts client tool.
 * Requests READ_CONTACTS at runtime on first use; subsequent calls skip the prompt.
 */
@CapacitorPlugin(
    name = "Contacts",
    permissions = {
        @Permission(strings = {Manifest.permission.READ_CONTACTS}, alias = "contacts")
    }
)
public class ContactsPlugin extends Plugin {

    @PluginMethod
    public void getContacts(PluginCall call) {
        if (getPermissionState("contacts") != PermissionState.GRANTED) {
            requestPermissionForAlias("contacts", call, "contactsPermissionsCallback");
            return;
        }
        resolveContacts(call);
    }

    @PermissionCallback
    private void contactsPermissionsCallback(PluginCall call) {
        if (getPermissionState("contacts") == PermissionState.GRANTED) {
            resolveContacts(call);
        } else {
            call.reject("denied");
        }
    }

    private void resolveContacts(PluginCall call) {
        String query = call.getString("query", "");
        JSArray contacts = new JSArray();

        Uri uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI;
        String[] projection = {
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        };

        String selection = null;
        String[] selectionArgs = null;
        if (query != null && !query.isEmpty()) {
            selection = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?";
            selectionArgs = new String[]{"%" + query + "%"};
        }

        try (Cursor cursor = getContext().getContentResolver().query(
                uri, projection, selection, selectionArgs,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {

            if (cursor != null) {
                int nameIdx = cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                int numIdx = cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.NUMBER);
                int count = 0;
                while (cursor.moveToNext() && count < 20) {
                    JSObject c = new JSObject();
                    c.put("name", cursor.getString(nameIdx));
                    c.put("phone", cursor.getString(numIdx));
                    contacts.put(c);
                    count++;
                }
            }
        } catch (Exception e) {
            call.reject("Error reading contacts: " + e.getMessage());
            return;
        }

        JSObject result = new JSObject();
        result.put("contacts", contacts);
        call.resolve(result);
    }
}
