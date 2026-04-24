package com.omnilm.loudmic;

import android.content.Context;
import android.media.AudioManager;

import com.aliucord.entities.Plugin;

/**
 * Aliucord-native loud mic helper.
 *
 * This plugin intentionally only toggles system communication audio behavior because
 * raw microphone preamp control is device/native dependent and not reliably exposed.
 */
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

        // Snapshot current device audio state so we can restore it in stop().
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
