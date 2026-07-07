package com.tomsense.app;

import android.service.voice.VoiceInteractionService;

/**
 * The bind target that makes TomSense selectable as the device's
 * "Digital assistant app" (Settings -> Apps -> Default apps -> Digital
 * assistant app). The system binds this service; the actual assist UI is
 * produced on demand by TomSenseVoiceSessionService / TomSenseVoiceSession.
 *
 * No logic is needed here — its mere presence (with the matching
 * @xml/voice_interaction_service meta-data) is what registers TomSense as an
 * available assistant.
 */
public class TomSenseVoiceInteractionService extends VoiceInteractionService {
}
