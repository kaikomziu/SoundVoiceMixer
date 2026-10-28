package com.soundvoice.mixer;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** 音声ファイル (mp3/wav/ogg/m4a など) を 48kHz モノラル float に変換する。 */
final class SoundLoader {
    private SoundLoader() {}

    static float[] load(Context ctx, Uri uri) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(ctx, uri, null);
        MediaFormat format = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                ex.selectTrack(i);
                format = f;
                break;
            }
        }
        if (format == null) {
            ex.release();
            throw new IOException("音声が見つかりません");
        }

        MediaCodec codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
        codec.configure(format, null, null, 0);
        codec.start();
        int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        int encoding = AudioFormat.ENCODING_PCM_16BIT;
        FloatList pcm = new FloatList();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        try {
            while (true) {
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(in);
                        int n = buf == null ? -1 : ex.readSampleData(buf, 0);
                        if (n < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int out = codec.dequeueOutputBuffer(info, 10_000);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    }
                } else if (out >= 0) {
                    ByteBuffer buf = codec.getOutputBuffer(out);
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset).limit(info.offset + info.size);
                        appendMono(pcm, buf.slice().order(ByteOrder.nativeOrder()), channels, encoding);
                    }
                    codec.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
        } finally {
            codec.stop();
            codec.release();
            ex.release();
        }
        return resample(pcm.toArray(), rate, AudioEngine.RATE);
    }

    private static void appendMono(FloatList dst, ByteBuffer b, int channels, int encoding) {
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            java.nio.FloatBuffer f = b.asFloatBuffer();
            while (f.remaining() >= channels) {
                float s = 0;
                for (int c = 0; c < channels; c++) s += f.get();
                dst.add(s / channels);
            }
        } else {
            java.nio.ShortBuffer sh = b.asShortBuffer();
            while (sh.remaining() >= channels) {
                float s = 0;
                for (int c = 0; c < channels; c++) s += sh.get() / 32768f;
                dst.add(s / channels);
            }
        }
    }

    private static float[] resample(float[] x, int src, int dst) {
        if (src == dst || x.length == 0) return x;
        int n = (int) ((long) x.length * dst / src);
        float[] y = new float[n];
        double step = (double) src / dst;
        for (int i = 0; i < n; i++) {
            double pos = i * step;
            int i0 = (int) pos;
            int i1 = Math.min(i0 + 1, x.length - 1);
            float fr = (float) (pos - i0);
            y[i] = x[i0] * (1 - fr) + x[i1] * fr;
        }
        return y;
    }

    private static final class FloatList {
        private float[] a = new float[1 << 16];
        private int size;

        void add(float v) {
            if (size == a.length) a = java.util.Arrays.copyOf(a, a.length * 2);
            a[size++] = v;
        }

        float[] toArray() {
            return java.util.Arrays.copyOf(a, size);
        }
    }
}
