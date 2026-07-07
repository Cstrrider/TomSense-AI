package com.tomsense.app;

import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

/**
 * Creates a fresh assist session each time the assist gesture fires.
 * Referenced by @xml/voice_interaction_service's sessionService attribute.
 */
public class TomSenseVoiceSessionService extends VoiceInteractionSessionService {
    @Override
    public VoiceInteractionSession onNewSession(Bundle args) {
        return new TomSenseVoiceSession(this);
    }
}
