package com.tomsense.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;

/**
 * Thin shim: when the assist gesture fires, immediately hand off to
 * AssistActivity (a normal translucent activity) and dismiss this session.
 *
 * AssistActivity — rather than this session's own window — hosts the overlay
 * because the panel is a *typing* surface. Activities handle the soft keyboard
 * cleanly; VoiceInteractionSession windows are notoriously flaky with IME
 * input.
 *
 * The session also receives the assist screenshot (onHandleScreenshot) and
 * holds it in ScreenCapture for the overlay's "use screen" feature.
 */
public class TomSenseVoiceSession extends VoiceInteractionSession {

    public TomSenseVoiceSession(Context context) {
        super(context);
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        // Fresh invocation — drop any screenshot held from a previous one.
        ScreenCapture.clear();
        // …and have the overlay start a new chat if its activity is still alive.
        AssistActivity.requestNewChat();
        launchPanel();
    }

    /**
     * Delivered shortly after onShow (when the user's "use screenshot" assist
     * setting is on) with a snapshot of the screen the user was looking at.
     * Held for the /assist overlay to attach on demand.
     */
    @Override
    public void onHandleScreenshot(Bitmap screenshot) {
        super.onHandleScreenshot(screenshot);
        if (screenshot != null) {
            ScreenCapture.store(screenshot);
        }
    }

    private void launchPanel() {
        Intent intent = new Intent(getContext(), AssistActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // The documented way to launch an activity from a voice session.
            startAssistantActivity(intent);
        } else {
            getContext().startActivity(intent);
        }
        // Dismiss the (empty) session window — AssistActivity owns the UI now.
        hide();
    }
}
