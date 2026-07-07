package com.tomsense.app;

import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;

/**
 * Holds the most recent assist screenshot (downscaled JPEG) for the overlay to
 * pick up on demand. Same-process hand-off: TomSenseVoiceSession writes it when
 * Android delivers the assist screenshot; the AssistActivity JS bridge reads it
 * when the /assist page's "use screen" chip is tapped.
 *
 * Nothing leaves the device here — the bytes only reach the network if the
 * user explicitly attaches them, and then only over the app's normal upload.
 */
final class ScreenCapture {

    /** Long-edge cap for the stored screenshot — keeps the upload small while
     *  staying legible to a vision model. */
    private static final int MAX_EDGE = 1024;

    private static volatile byte[] jpeg;

    private ScreenCapture() {}

    /** Drop any held screenshot — called at the start of each new invocation. */
    static void clear() {
        jpeg = null;
    }

    /** Downscale + JPEG-compress the screen snapshot and hold the bytes. */
    static void store(Bitmap bmp) {
        try {
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            float scale = Math.min(1f, (float) MAX_EDGE / Math.max(w, h));
            Bitmap scaled = scale < 1f
                    ? Bitmap.createScaledBitmap(
                            bmp,
                            Math.max(1, Math.round(w * scale)),
                            Math.max(1, Math.round(h * scale)),
                            true)
                    : bmp;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out);
            jpeg = out.toByteArray();
        } catch (Exception e) {
            jpeg = null;
        }
    }

    /** The held JPEG bytes, or null if no screenshot is available. */
    static byte[] get() {
        return jpeg;
    }
}
