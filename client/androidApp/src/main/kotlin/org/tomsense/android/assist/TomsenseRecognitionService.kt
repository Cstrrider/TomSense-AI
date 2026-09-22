package org.tomsense.android.assist

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * The speech recogniser the platform insists an assistant app ships.
 *
 * Android couples the two: a VoiceInteractionService is expected to be
 * accompanied by a RecognitionService, and that coupling is deliberate and
 * documented as not going to change. `interaction_service.xml` previously
 * pointed `recognitionService` at TomsenseInteractionService — which is a
 * VoiceInteractionService, not a RecognitionService — so the declaration named
 * a class that cannot fulfil the contract. The visible symptom of getting this
 * wrong is not a crash: it is TomSense failing to appear, or failing to stick,
 * in the digital-assistant picker, which would leave BOTH the power-button
 * hold and the corner-swipe gesture dead with nothing to indicate why.
 *
 * It does not recognise speech, and says so immediately rather than pretending.
 * There is no on-device STT yet and the duplex voice path is unbuilt, so any
 * app that binds this expecting dictation gets an honest error on the first
 * callback instead of a session that hangs waiting for a result that is never
 * coming.
 *
 * When voice lands (spec M5) this is where it goes.
 */
class TomsenseRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        // ERROR_CLIENT rather than silence: SpeechRecognizer has no timeout of
        // its own, so a caller that gets neither a result nor an error waits
        // forever.
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
