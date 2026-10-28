package com.soundvoice.mixer;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Process;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * マイク + スマホ内の音(AudioPlaybackCapture) + サウンドボードをミックスし、
 * エフェクトをかけてイヤホン/スピーカーへ鳴らす。録音もできる。
 */
final class AudioEngine implements Runnable {
    static final int RATE = 48000;
    static final int BLOCK = RATE / 100;          // 10ms
    private static final int PRIME = BLOCK * 3;

    static volatile AudioEngine current;

    private final Params p = Params.INSTANCE;
    private final MediaProjection projection;
    private AudioRecord mic, device;
    private AudioTrack track;
    private Thread thread;
    private volatile boolean running;
    private volatile WavWriter recorder;
    private File recordingFile;

    /** 0=スマホの音 1=マイク 2=出力 のピーク値 (メーター用)。 */
    final float[] levels = new float[3];
    volatile String error;

    private final List<float[]> voiceData = new ArrayList<>();
    private final List<int[]> voicePos = new ArrayList<>();

    // スマホ内の音用のジッタバッファ
    private final float[] fifo = new float[RATE];
    private int fifoRead, fifoCount;
    private boolean primed;

    AudioEngine(MediaProjection projection) {
        this.projection = projection;
    }

    @SuppressLint("MissingPermission")
    void start() {
        AudioFormat in = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
        int inBuf = Math.max(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT), BLOCK * 2 * 4);

        if (p.micOn) {
            mic = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(in)
                    .setBufferSizeInBytes(inBuf)
                    .build();
        }
        if (projection != null) {
            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .excludeUid(Process.myUid())
                    .build();
            device = new AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(cfg)
                    .setAudioFormat(in)
                    .setBufferSizeInBytes(inBuf * 4)
                    .build();
        }
        int outBuf = Math.max(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT), BLOCK * 4 * 4);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(outBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build();

        if (mic != null) mic.startRecording();
        if (device != null) device.startRecording();
        track.play();
        running = true;
        thread = new Thread(this, "SoundVoiceAudio");
        thread.start();
        current = this;
    }

    void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(1000);
            } catch (InterruptedException ignored) {
            }
        }
        for (AudioRecord r : new AudioRecord[]{mic, device}) {
            if (r != null) {
                try {
                    r.stop();
                } catch (IllegalStateException ignored) {
                }
                r.release();
            }
        }
        if (track != null) {
            try {
                track.stop();
            } catch (IllegalStateException ignored) {
            }
            track.release();
        }
        if (current == this) current = null;
    }

    boolean isRunning() {
        return running;
    }

    boolean hasDeviceCapture() {
        return device != null;
    }

    // ---- サウンドボード ----
    void play(float[] data) {
        synchronized (voiceData) {
            voiceData.add(data);
            voicePos.add(new int[]{0});
        }
    }

    void stopSounds() {
        synchronized (voiceData) {
            voiceData.clear();
            voicePos.clear();
        }
    }

    private void mixSounds(float[] out) {
        java.util.Arrays.fill(out, 0);
        synchronized (voiceData) {
            Iterator<float[]> d = voiceData.iterator();
            Iterator<int[]> ps = voicePos.iterator();
            while (d.hasNext()) {
                float[] data = d.next();
                int[] pos = ps.next();
                int n = Math.min(out.length, data.length - pos[0]);
                for (int i = 0; i < n; i++) out[i] += data[pos[0] + i];
                pos[0] += out.length;
                if (pos[0] >= data.length) {
                    d.remove();
                    ps.remove();
                }
            }
        }
    }

    // ---- 録音 ----
    void startRecording(File file) throws IOException {
        recordingFile = file;
        recorder = new WavWriter(file, RATE);
    }

    /** 録音を止めて、そのファイルを返す (録音していなければ null)。 */
    File stopRecording() {
        WavWriter r = recorder;
        recorder = null;
        if (r == null) return null;
        r.close();
        return recordingFile;
    }

    boolean isRecording() {
        return recorder != null;
    }

    // ---- 音声スレッド ----
    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        short[] pcm = new short[BLOCK * 4];
        float[] d = new float[BLOCK], m = new float[BLOCK], sb = new float[BLOCK];
        float[] dDist = new float[BLOCK], mix = new float[BLOCK], mon = new float[BLOCK];
        Dsp.NoiseGate gate = new Dsp.NoiseGate(RATE);
        Dsp.Echo echo = new Dsp.Echo(RATE);
        Dsp.VoiceChanger voice = new Dsp.VoiceChanger(RATE);
        boolean echoWasOn = false;

        while (running) {
            // マイクがあればそれを時計にし、無ければスマホの音、どちらも無ければ出力の書き込みで待つ
            if (mic != null) {
                if (!readFully(mic, pcm, m)) break;
            } else {
                java.util.Arrays.fill(m, 0);
            }
            if (device != null) {
                if (mic == null) {
                    if (!readFully(device, pcm, d)) break;
                } else {
                    readDevice(pcm, d);
                }
            } else {
                java.util.Arrays.fill(d, 0);
            }

            float dv = p.deviceMute ? 0 : p.deviceVol;
            for (int i = 0; i < BLOCK; i++) d[i] *= dv;

            if (p.gateOn) gate.process(m, BLOCK, p.gateDb);
            if (p.vcOn) voice.process(m, BLOCK, p);
            float mv = p.micMute ? 0 : p.micVol;
            for (int i = 0; i < BLOCK; i++) m[i] *= mv;
            if (p.echoOn) {
                if (!echoWasOn) echo.reset();
                echo.process(m, BLOCK, p.echoMs, p.echoFb, p.echoMix);
            }
            echoWasOn = p.echoOn;
            boolean dist = p.distOn || p.distHold;
            if (dist && p.distTarget == 1) Dsp.distort(m, BLOCK, p.distMode, p.distAmount);

            mixSounds(sb);
            for (int i = 0; i < BLOCK; i++) sb[i] *= p.sbVol;

            System.arraycopy(d, 0, dDist, 0, BLOCK);
            if (dist && p.distTarget == 2) Dsp.distort(dDist, BLOCK, p.distMode, p.distAmount);

            combine(mix, dDist, m, sb, true, true, true, dist);
            combine(mon, dDist, m, sb, p.monDevice, p.monMic, p.monSb, dist);
            float vol = p.monitorVol;
            for (int i = 0; i < BLOCK; i++) mon[i] *= vol;

            WavWriter r = recorder;
            if (r != null) r.write(mix, BLOCK);

            levels[0] = Math.max(levels[0] * 0.85f, peak(d));
            levels[1] = Math.max(levels[1] * 0.85f, peak(m));
            levels[2] = Math.max(levels[2] * 0.85f, peak(mix));

            if (track.write(mon, 0, BLOCK, AudioTrack.WRITE_BLOCKING) < 0) {
                error = "出力エラー";
                break;
            }
        }
        running = false;
    }

    private void combine(float[] out, float[] d, float[] m, float[] sb,
                         boolean useD, boolean useM, boolean useSb, boolean dist) {
        for (int i = 0; i < BLOCK; i++) {
            out[i] = (useD ? d[i] : 0) + (useM ? m[i] : 0) + (useSb ? sb[i] : 0);
        }
        if (dist && p.distTarget == 0) Dsp.distort(out, BLOCK, p.distMode, p.distAmount);
        float master = p.masterVol;
        for (int i = 0; i < BLOCK; i++) out[i] = Dsp.clamp(out[i] * master, -1, 1);
    }

    private boolean readFully(AudioRecord rec, short[] pcm, float[] out) {
        int got = 0;
        while (got < BLOCK && running) {
            int n = rec.read(pcm, got, BLOCK - got, AudioRecord.READ_BLOCKING);
            if (n < 0) {
                error = "録音エラー (" + n + ")";
                return false;
            }
            got += n;
        }
        for (int i = 0; i < BLOCK; i++) out[i] = pcm[i] / 32768f;
        return true;
    }

    /** スマホの音を読めるだけ読んでバッファに溜め、BLOCK 分取り出す。 */
    private void readDevice(short[] pcm, float[] out) {
        int n = device.read(pcm, 0, pcm.length, AudioRecord.READ_NON_BLOCKING);
        int size = fifo.length;
        for (int i = 0; i < Math.max(n, 0); i++) {
            fifo[(fifoRead + fifoCount) % size] = pcm[i] / 32768f;
            if (fifoCount < size) fifoCount++;
            else fifoRead = (fifoRead + 1) % size;
        }
        // 溜まりすぎたら古いものを捨てる (遅延対策)
        while (fifoCount > BLOCK * 12) {
            fifoRead = (fifoRead + 1) % size;
            fifoCount--;
        }
        java.util.Arrays.fill(out, 0);
        if (!primed && fifoCount < PRIME) return;
        primed = true;
        int take = Math.min(BLOCK, fifoCount);
        for (int i = 0; i < take; i++) out[i] = fifo[(fifoRead + i) % size];
        fifoRead = (fifoRead + take) % size;
        fifoCount -= take;
        if (take < BLOCK) primed = false;
    }

    private static float peak(float[] x) {
        float v = 0;
        for (float s : x) v = Math.max(v, Math.abs(s));
        return v;
    }
}
