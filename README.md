# OmniALM
Loud on AliuCord Dc
# Loud Mic (Aliucord Plugin)

This repository contains an **Aliucord-native Java plugin** called `LoudMic`.

> You asked for the new README without diff-style removed lines, so this is the clean full file content.

## What it does

When enabled, `LoudMic` applies an aggressive communication-audio profile:

- Sets audio mode to `MODE_IN_COMMUNICATION`
- Turns speakerphone off
- Unmutes the microphone
- Pushes `STREAM_VOICE_CALL` volume to the device max

When disabled/unloaded, it restores the previous values it captured on start.

## Plugin source code

`src/main/java/com/omnilm/loudmic/LoudMic.java`

```java
package com.omnilm.loudmic;

import android.content.Context;
import android.media.AudioManager;

import com.aliucord.entities.Plugin;

@SuppressWarnings("unused")
public class LoudMic extends Plugin {
    private Integer previousMode;
    private Boolean previousSpeakerphone;
    private Boolean previousMicMute;
    private Integer previousVoiceCallVolume;

    @Override
    public void start(Context context) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            logger.error("AudioManager unavailable; cannot apply loud mic profile.");
            return;
        }

        previousMode = audioManager.getMode();
        previousSpeakerphone = audioManager.isSpeakerphoneOn();
        previousMicMute = audioManager.isMicrophoneMute();
        previousVoiceCallVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL);

        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(false);
            audioManager.setMicrophoneMute(false);

            int maxVoiceCall = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
            if (maxVoiceCall > 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVoiceCall, 0);
            }

            logger.info("LoudMic profile applied (communication mode + max voice-call volume).");
        } catch (Throwable th) {
            logger.error("Failed applying LoudMic profile", th);
        }
    }

    @Override
    public void stop(Context context) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;

        try {
            if (previousMode != null) audioManager.setMode(previousMode);
            if (previousSpeakerphone != null) audioManager.setSpeakerphoneOn(previousSpeakerphone);
            if (previousMicMute != null) audioManager.setMicrophoneMute(previousMicMute);

            if (previousVoiceCallVolume != null) {
                int maxVoiceCall = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
                int restoredVolume = Math.max(0, Math.min(previousVoiceCallVolume, maxVoiceCall));
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, restoredVolume, 0);
            }

            logger.info("LoudMic profile restored.");
        } catch (Throwable th) {
            logger.error("Failed restoring LoudMic profile", th);
        }
    }
}
