package com.soundvoice.mixer;

/** PC版 soundvoice_mixer.py と同じエフェクト群 (モノラル float, その場で書き換え)。 */
final class Dsp {
    private Dsp() {}

    // ---- 音割れ ----
    static final String[] DIST_MODES = {"ハードクリップ", "ソフト", "ビットクラッシュ"};

    /** amount は 0〜100。 */
    static void distort(float[] x, int n, int mode, float amount) {
        if (mode == 2) {
            float drive = (float) Math.pow(10, amount * 0.2 / 20);   // 最大 +20dB
            double bits = 16 - amount / 100.0 * 13;                  // 16bit → 3bit
            float step = (float) (2.0 / Math.pow(2, bits));
            int hold = 1 + (int) (amount / 100 * 15);                // サンプルレート低下
            float held = 0;
            for (int i = 0; i < n; i++) {
                if (i % hold == 0) {
                    float v = clamp(x[i] * drive, -1, 1);
                    held = Math.round(v / step) * step;
                }
                x[i] = held;
            }
            return;
        }
        float drive = (float) Math.pow(10, amount * 0.45 / 20);      // 最大 +45dB
        for (int i = 0; i < n; i++) {
            x[i] = mode == 1 ? (float) Math.tanh(x[i] * drive)
                             : clamp(x[i] * drive, -0.8f, 0.8f) * 1.25f;  // ガチ音割れ
        }
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ---- ノイズゲート ----
    static final class NoiseGate {
        private final int rate;
        private float gain = 1;
        private int hold;

        NoiseGate(int rate) { this.rate = rate; }

        void process(float[] x, int n, float thresholdDb) {
            double sum = 0;
            for (int i = 0; i < n; i++) sum += x[i] * x[i];
            double db = 20 * Math.log10(Math.sqrt(sum / n) + 1e-9);
            if (db > thresholdDb) hold = (int) (rate * 0.15);
            else hold -= n;
            float target = hold > 0 ? 1 : 0;
            float next = gain + (target - gain) * (target > gain ? 0.6f : 0.15f);
            for (int i = 0; i < n; i++) x[i] *= gain + (next - gain) * i / n;
            gain = next;
        }
    }

    // ---- エコー ----
    static final class Echo {
        private final int rate;
        private final float[] buf;
        private int pos;

        Echo(int rate) {
            this.rate = rate;
            buf = new float[rate * 2];
        }

        void reset() { java.util.Arrays.fill(buf, 0); }

        void process(float[] x, int n, float delayMs, float feedback, float mix) {
            int size = buf.length;
            int delay = Math.max(1, (int) (delayMs / 1000 * rate));
            for (int i = 0; i < n; i++) {
                float d = buf[(pos - delay + size) % size];
                buf[pos] = x[i] + d * feedback;
                x[i] += d * mix;
                pos = (pos + 1) % size;
            }
        }
    }

    // ---- 2次IIRフィルター (RBJ) ----
    static final class Biquad {
        private float b0, b1, b2, a1, a2, z1, z2;

        static Biquad lowpass(int rate, double f) { return make(rate, f, false); }
        static Biquad highpass(int rate, double f) { return make(rate, f, true); }

        private static Biquad make(int rate, double f, boolean high) {
            double w = 2 * Math.PI * f / rate, cos = Math.cos(w), alpha = Math.sin(w) / (2 * 0.7071);
            double a0 = 1 + alpha;
            Biquad q = new Biquad();
            double b = high ? (1 + cos) / 2 : (1 - cos) / 2;
            q.b0 = (float) (b / a0);
            q.b1 = (float) ((high ? -(1 + cos) : (1 - cos)) / a0);
            q.b2 = q.b0;
            q.a1 = (float) (-2 * cos / a0);
            q.a2 = (float) ((1 - alpha) / a0);
            return q;
        }

        float process(float x) {
            float y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }
    }

    // ---- リバーブ (シュレーダー型) ----
    static final class Reverb {
        private final float[][] combs;
        private final int[] combPos;
        private final float[][] aps;
        private final int[] apPos;

        Reverb(int rate) {
            double[] combMs = {29.7, 37.1, 41.1, 43.7};
            double[] apMs = {5.0, 1.7};
            combs = new float[combMs.length][];
            combPos = new int[combMs.length];
            for (int i = 0; i < combMs.length; i++) combs[i] = new float[(int) (rate * combMs[i] / 1000)];
            aps = new float[apMs.length][];
            apPos = new int[apMs.length];
            for (int i = 0; i < apMs.length; i++) aps[i] = new float[(int) (rate * apMs[i] / 1000)];
        }

        float process(float x) {
            float wet = 0;
            for (int c = 0; c < combs.length; c++) {
                float[] b = combs[c];
                float y = x + 0.84f * b[combPos[c]];
                b[combPos[c]] = y;
                combPos[c] = (combPos[c] + 1) % b.length;
                wet += y;
            }
            wet *= 0.25f;
            for (int a = 0; a < aps.length; a++) {
                float[] b = aps[a];
                float delayed = b[apPos[a]];
                float w = wet + 0.7f * delayed;
                b[apPos[a]] = w;
                apPos[a] = (apPos[a] + 1) % b.length;
                wet = delayed - 0.7f * w;
            }
            return wet;
        }
    }

    // ---- ボイスチェンジャー ----
    static final String[] FILTERS = {"なし", "電話", "ラジオ", "メガホン", "こもり声", "シャカシャカ"};

    /** プリセット名と {ピッチ(半音), 震え%, ロボット%, ロボットHz, フィルター番号, リバーブ%}。 */
    static final String[] PRESET_NAMES = {
            "ノーマル", "女声風(高め)", "男声風(低め)", "子ども", "ヘリウム", "魔王", "ロボット",
            "宇宙人", "おばけ", "電話", "ラジオ", "メガホン", "洞窟", "壁の向こう"};
    static final float[][] PRESETS = {
            {0, 0, 0, 50, 0, 0}, {5, 0, 0, 50, 0, 0}, {-5, 0, 0, 50, 0, 0}, {8, 0, 0, 50, 0, 0},
            {12, 0, 0, 50, 0, 0}, {-10, 0, 0, 50, 0, 35}, {0, 0, 100, 60, 0, 10},
            {7, 40, 60, 30, 0, 20}, {-2, 100, 0, 50, 0, 60}, {0, 0, 0, 50, 1, 0},
            {0, 0, 0, 50, 2, 0}, {0, 0, 0, 50, 3, 15}, {0, 0, 0, 50, 0, 85}, {0, 0, 0, 50, 4, 20}};

    static final class VoiceChanger {
        private static final int SIZE = 1 << 16;
        private final int rate;
        private final int window;
        private final float[] buf = new float[SIZE];
        private int pos;
        private double phase, lfo, ring;
        private final Reverb reverb;
        private int filterId = -1;
        private Biquad[] filters = new Biquad[0];

        VoiceChanger(int rate) {
            this.rate = rate;
            window = rate * 40 / 1000;
            reverb = new Reverb(rate);
        }

        void process(float[] x, int n, Params p) {
            pitch(x, n, p.vcPitch, p.vcVibrato / 100f);
            if (p.vcRobot > 0) {
                float a = p.vcRobot / 100f;
                double step = 2 * Math.PI * p.vcRobotHz / rate;
                for (int i = 0; i < n; i++) {
                    ring += step;
                    x[i] = x[i] * (1 - a) + x[i] * (float) Math.sin(ring) * a * 1.4f;
                }
                ring %= 2 * Math.PI;
            }
            filter(x, n, p.vcFilter);
            if (p.vcReverb > 0) {
                float a = p.vcReverb / 100f;
                for (int i = 0; i < n; i++) x[i] = x[i] * (1 - 0.4f * a) + reverb.process(x[i]) * a;
            }
        }

        /** 2つの読み出しヘッドをクロスフェードするディレイライン型ピッチシフタ。 */
        private void pitch(float[] x, int n, float semitones, float vibrato) {
            boolean bypass = semitones == 0 && vibrato == 0;
            double lfoStep = 2 * Math.PI * 6.0 / rate;
            for (int i = 0; i < n; i++) {
                buf[pos & (SIZE - 1)] = x[i];
                if (!bypass) {
                    lfo += lfoStep;
                    double ratio = Math.pow(2, (semitones + vibrato * Math.sin(lfo)) / 12);
                    phase += (1 - ratio) / window;
                    phase -= Math.floor(phase);
                    float out = 0;
                    for (int h = 0; h < 2; h++) {
                        double ph = h == 0 ? phase : (phase + 0.5) % 1.0;
                        double rp = pos - ph * window;
                        long i0 = (long) Math.floor(rp);
                        float fr = (float) (rp - i0);
                        float s = buf[(int) (i0 & (SIZE - 1))] * (1 - fr) + buf[(int) ((i0 + 1) & (SIZE - 1))] * fr;
                        double g = Math.sin(Math.PI * ph);
                        out += s * (float) (g * g);
                    }
                    x[i] = out;
                }
                pos++;
            }
            lfo %= 2 * Math.PI;
        }

        private void filter(float[] x, int n, int id) {
            if (id <= 0) return;
            if (id != filterId) {
                filterId = id;
                switch (id) {
                    case 1: filters = band(400, 3000); break;
                    case 2: filters = band(600, 4000); break;
                    case 3: filters = band(500, 2500); break;
                    case 4: filters = new Biquad[]{Biquad.lowpass(rate, 700), Biquad.lowpass(rate, 700)}; break;
                    default: filters = new Biquad[]{Biquad.highpass(rate, 2000), Biquad.highpass(rate, 2000)};
                }
            }
            float drive = id == 1 ? 1.5f : id == 2 ? 3f : id == 3 ? 5f : 1f;
            float norm = (float) Math.tanh(drive);
            for (int i = 0; i < n; i++) {
                float y = x[i];
                for (Biquad f : filters) y = f.process(y);
                x[i] = drive > 1 ? (float) Math.tanh(y * drive) / norm * 0.7f : y;
            }
        }

        private Biquad[] band(double lo, double hi) {
            return new Biquad[]{Biquad.highpass(rate, lo), Biquad.highpass(rate, lo),
                    Biquad.lowpass(rate, hi), Biquad.lowpass(rate, hi)};
        }
    }
}
