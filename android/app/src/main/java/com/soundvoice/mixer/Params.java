package com.soundvoice.mixer;

import android.content.SharedPreferences;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** UIスレッドが書き、音声スレッドが読む設定値。音量は 1.0 = 100%。 */
final class Params {
    static final Params INSTANCE = new Params();

    volatile boolean deviceOn = true, micOn = true;
    volatile float deviceVol = 1, micVol = 1, sbVol = 1, masterVol = 1, monitorVol = 1;
    volatile boolean deviceMute, micMute;
    volatile boolean monMic = true, monSb = true, monDevice;

    volatile boolean distOn, distHold;
    volatile int distMode, distTarget;            // distTarget: 0=全体 1=マイクのみ 2=スマホの音のみ
    volatile float distAmount = 50;

    volatile boolean gateOn;
    volatile float gateDb = -45;

    volatile boolean echoOn;
    volatile float echoMs = 250, echoFb = 0.4f, echoMix = 0.5f;

    volatile boolean vcOn;
    volatile int vcPreset, vcFilter;
    volatile float vcPitch, vcVibrato, vcRobot, vcRobotHz = 50, vcReverb;

    private Params() {}

    void applyPreset(int i) {
        float[] v = Dsp.PRESETS[i];
        vcPreset = i;
        vcPitch = v[0];
        vcVibrato = v[1];
        vcRobot = v[2];
        vcRobotHz = v[3];
        vcFilter = (int) v[4];
        vcReverb = v[5];
        vcOn = i != 0;
    }

    void load(SharedPreferences sp) {
        try {
            for (Field f : fields()) {
                if (!sp.contains(f.getName())) continue;
                if (f.getType() == float.class) f.setFloat(this, sp.getFloat(f.getName(), 0));
                else if (f.getType() == int.class) f.setInt(this, sp.getInt(f.getName(), 0));
                else if (f.getType() == boolean.class) f.setBoolean(this, sp.getBoolean(f.getName(), false));
            }
        } catch (IllegalAccessException ignored) {
        }
        distHold = false;
    }

    void save(SharedPreferences sp) {
        SharedPreferences.Editor e = sp.edit();
        try {
            for (Field f : fields()) {
                if (f.getType() == float.class) e.putFloat(f.getName(), f.getFloat(this));
                else if (f.getType() == int.class) e.putInt(f.getName(), f.getInt(this));
                else if (f.getType() == boolean.class) e.putBoolean(f.getName(), f.getBoolean(this));
            }
        } catch (IllegalAccessException ignored) {
        }
        e.apply();
    }

    private static Field[] fields() {
        java.util.List<Field> out = new java.util.ArrayList<>();
        for (Field f : Params.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) out.add(f);
        }
        return out.toArray(new Field[0]);
    }
}
