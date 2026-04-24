package com.omnilm.loudmic;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;

import com.aliucord.entities.Plugin;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Native loud mic hook for Discord voice capture.
 *
 * The old implementation only changed communication routing and voice-call playback volume,
 * which does not amplify microphone PCM being sent to voice chat. This version hooks the
 * AudioRecord capture path directly and multiplies captured samples after every read.
 */
@SuppressWarnings("unused")
public class LoudMic extends Plugin {
    private static final float PCM_GAIN_MULTIPLIER = 10.0f; // 1000%
    private static final boolean FORCE_ENABLE_AGC = true;
    private static final boolean FORCE_DISABLE_NS = true;
    private static final boolean FORCE_DISABLE_AEC = true;

    private final Set<XC_MethodHook.Unhook> unhooks = new HashSet<>();
    private final WeakHashMap<AudioRecord, CaptureEffects> captureEffects = new WeakHashMap<>();

    private AudioManager audioManager;
    private Integer previousMode;
    private Boolean previousMicMute;

    @Override
    public void start(Context context) {
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        snapshotAudioState();
        applyCommunicationAudioMode();

        try {
            XposedBridge.disableProfileSaver();
        } catch (Throwable th) {
            logger.error("Failed to disable profile saver; hooks may be less reliable on some devices.", th);
        }

        installHooks();
        logger.info("LoudMic hooks installed with " + PCM_GAIN_MULTIPLIER + "x PCM gain.");
    }

    @Override
    public void stop(Context context) {
        for (XC_MethodHook.Unhook unhook : new ArrayList<>(unhooks)) {
            try {
                unhook.unhook();
            } catch (Throwable th) {
                logger.error("Failed to remove LoudMic hook cleanly.", th);
            }
        }
        unhooks.clear();

        releaseAllEffects();
        restoreAudioState();
    }

    private void snapshotAudioState() {
        if (audioManager == null) return;

        try {
            previousMode = audioManager.getMode();
            previousMicMute = audioManager.isMicrophoneMute();
        } catch (Throwable th) {
            logger.error("Failed to snapshot existing audio state.", th);
        }
    }

    private void applyCommunicationAudioMode() {
        if (audioManager == null) return;

        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setMicrophoneMute(false);
        } catch (Throwable th) {
            logger.error("Failed to apply communication audio mode.", th);
        }
    }

    private void restoreAudioState() {
        if (audioManager == null) return;

        try {
            if (previousMode != null) audioManager.setMode(previousMode);
            if (previousMicMute != null) audioManager.setMicrophoneMute(previousMicMute);
        } catch (Throwable th) {
            logger.error("Failed to restore audio state.", th);
        }
    }

    private void installHooks() {
        unhooks.addAll(XposedBridge.hookAllMethods(AudioRecord.class, "startRecording", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                ensureCaptureEffects((AudioRecord) param.thisObject);
            }
        }));

        unhooks.addAll(XposedBridge.hookAllMethods(AudioRecord.class, "read", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                amplifyReadResult(param);
            }
        }));

        XC_MethodHook cleanupHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                releaseEffects((AudioRecord) param.thisObject);
            }
        };

        unhooks.addAll(XposedBridge.hookAllMethods(AudioRecord.class, "stop", cleanupHook));
        unhooks.addAll(XposedBridge.hookAllMethods(AudioRecord.class, "release", cleanupHook));
    }

    private void amplifyReadResult(XC_MethodHook.MethodHookParam param) {
        Object rawResult = param.getResult();
        if (!(rawResult instanceof Integer)) return;

        int amountRead = (Integer) rawResult;
        if (amountRead <= 0) return;

        AudioRecord audioRecord = (AudioRecord) param.thisObject;
        if (!shouldProcess(audioRecord)) return;

        ensureCaptureEffects(audioRecord);

        Object buffer = param.args[0];
        try {
            if (buffer instanceof short[]) {
                int offset = intArg(param.args, 1);
                amplifyShortArray((short[]) buffer, offset, amountRead, PCM_GAIN_MULTIPLIER);
                return;
            }

            if (buffer instanceof byte[]) {
                int offset = intArg(param.args, 1);
                amplifyPcm16ByteArray((byte[]) buffer, offset, amountRead, PCM_GAIN_MULTIPLIER);
                return;
            }

            if (buffer instanceof ByteBuffer) {
                amplifyByteBuffer((ByteBuffer) buffer, amountRead, PCM_GAIN_MULTIPLIER);
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && buffer instanceof float[]) {
                int offset = intArg(param.args, 1);
                amplifyFloatArray((float[]) buffer, offset, amountRead, PCM_GAIN_MULTIPLIER);
            }
        } catch (Throwable th) {
            logger.error("Failed to amplify captured mic buffer.", th);
        }
    }

    private boolean shouldProcess(AudioRecord audioRecord) {
        if (audioRecord == null) return false;

        try {
            if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) return false;
        } catch (Throwable ignored) {
            return false;
        }

        if (audioManager == null) return true;

        try {
            int mode = audioManager.getMode();
            return mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private void ensureCaptureEffects(AudioRecord audioRecord) {
        if (audioRecord == null || captureEffects.containsKey(audioRecord)) return;

        CaptureEffects effects = new CaptureEffects();
        try {
            int sessionId = audioRecord.getAudioSessionId();

            if (AutomaticGainControl.isAvailable()) {
                effects.agc = AutomaticGainControl.create(sessionId);
                if (effects.agc != null) effects.agc.setEnabled(FORCE_ENABLE_AGC);
            }

            if (NoiseSuppressor.isAvailable()) {
                effects.ns = NoiseSuppressor.create(sessionId);
                if (effects.ns != null) effects.ns.setEnabled(!FORCE_DISABLE_NS);
            }

            if (AcousticEchoCanceler.isAvailable()) {
                effects.aec = AcousticEchoCanceler.create(sessionId);
                if (effects.aec != null) effects.aec.setEnabled(!FORCE_DISABLE_AEC);
            }

            captureEffects.put(audioRecord, effects);
        } catch (Throwable th) {
            logger.error("Failed to attach capture effects to AudioRecord.", th);
            effects.release();
        }
    }

    private void releaseEffects(AudioRecord audioRecord) {
        CaptureEffects effects = captureEffects.remove(audioRecord);
        if (effects != null) effects.release();
    }

    private void releaseAllEffects() {
        List<CaptureEffects> effects = new ArrayList<>(captureEffects.values());
        captureEffects.clear();
        for (CaptureEffects effect : effects) {
            if (effect != null) effect.release();
        }
    }

    private static int intArg(Object[] args, int index) {
        if (args.length <= index || !(args[index] instanceof Integer)) return 0;
        return (Integer) args[index];
    }

    private static void amplifyShortArray(short[] audio, int offset, int count, float gain) {
        int start = Math.max(0, offset);
        int end = Math.min(audio.length, start + count);
        for (int i = start; i < end; i++) {
            audio[i] = saturatingShort(audio[i] * gain);
        }
    }

    private static void amplifyFloatArray(float[] audio, int offset, int count, float gain) {
        int start = Math.max(0, offset);
        int end = Math.min(audio.length, start + count);
        for (int i = start; i < end; i++) {
            float sample = audio[i] * gain;
            if (sample > 1.0f) sample = 1.0f;
            if (sample < -1.0f) sample = -1.0f;
            audio[i] = sample;
        }
    }

    private static void amplifyPcm16ByteArray(byte[] audio, int offsetBytes, int bytesRead, float gain) {
        int start = Math.max(0, offsetBytes);
        int end = Math.min(audio.length, start + bytesRead);
        end -= (end - start) % 2;

        for (int i = start; i < end; i += 2) {
            short sample = (short) ((audio[i] & 0xff) | (audio[i + 1] << 8));
            short amplified = saturatingShort(sample * gain);
            audio[i] = (byte) (amplified & 0xff);
            audio[i + 1] = (byte) ((amplified >>> 8) & 0xff);
        }
    }

    private static void amplifyByteBuffer(ByteBuffer audioBuffer, int bytesRead, float gain) {
        ByteBuffer duplicate = audioBuffer.duplicate().order(ByteOrder.nativeOrder());
        duplicate.position(0);
        duplicate.limit(Math.min(bytesRead, duplicate.capacity()));

        int sampleBytes = duplicate.remaining() - (duplicate.remaining() % 2);
        for (int i = 0; i < sampleBytes; i += 2) {
            short sample = duplicate.getShort(i);
            duplicate.putShort(i, saturatingShort(sample * gain));
        }
    }

    private static short saturatingShort(float sample) {
        if (sample > Short.MAX_VALUE) return Short.MAX_VALUE;
        if (sample < Short.MIN_VALUE) return Short.MIN_VALUE;
        return (short) Math.round(sample);
    }

    private static final class CaptureEffects {
        AutomaticGainControl agc;
        NoiseSuppressor ns;
        AcousticEchoCanceler aec;

        void release() {
            try {
                if (agc != null) agc.release();
            } catch (Throwable ignored) {
            }
            try {
                if (ns != null) ns.release();
            } catch (Throwable ignored) {
            }
            try {
                if (aec != null) aec.release();
            } catch (Throwable ignored) {
            }
        }
    }
}
