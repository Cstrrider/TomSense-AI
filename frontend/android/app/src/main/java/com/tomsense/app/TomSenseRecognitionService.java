package com.tomsense.app;

import android.content.Intent;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

/**
 * No-op speech recognizer. Android's <voice-interaction-service> config
 * requires a recognitionService to reference, but TomSense's assistant is
 * text-driven — so this stub simply reports "unavailable" if anything ever
 * routes speech recognition to it.
 */
public class TomSenseRecognitionService extends RecognitionService {
    @Override
    protected void onStartListening(Intent recognizerIntent, Callback listener) {
        try {
            listener.error(SpeechRecognizer.ERROR_CLIENT);
        } catch (Exception ignored) {
            // Callback may throw RemoteException across the binder; nothing
            // useful to do for a stub recognizer.
        }
    }

    @Override
    protected void onCancel(Callback listener) {
    }

    @Override
    protected void onStopListening(Callback listener) {
    }
}
