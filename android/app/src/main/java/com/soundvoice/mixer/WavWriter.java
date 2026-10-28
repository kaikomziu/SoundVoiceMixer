package com.soundvoice.mixer;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 16bit モノラル WAV の書き出し。 */
final class WavWriter {
    private final File file;
    private final int rate;
    private final OutputStream out;
    private final byte[] bytes = new byte[AudioEngine.BLOCK * 2];
    private long dataBytes;
    private boolean closed;

    WavWriter(File file, int rate) throws IOException {
        this.file = file;
        this.rate = rate;
        out = new BufferedOutputStream(new FileOutputStream(file), 1 << 16);
        out.write(new byte[44]);   // ヘッダは close 時に書く
    }

    synchronized void write(float[] x, int n) {
        if (closed) return;
        for (int i = 0; i < n; i++) {
            int v = Math.round(Dsp.clamp(x[i], -1, 1) * 32767);
            bytes[2 * i] = (byte) v;
            bytes[2 * i + 1] = (byte) (v >> 8);
        }
        try {
            out.write(bytes, 0, n * 2);
            dataBytes += n * 2L;
        } catch (IOException ignored) {
        }
    }

    synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            out.close();
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                raf.write(header());
            }
        } catch (IOException ignored) {
        }
    }

    private byte[] header() {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt((int) (36 + dataBytes)).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes()).putInt((int) dataBytes);
        return b.array();
    }

    /** 録音ファイルを 「ミュージック/SoundVoice」 にコピーし、表示名を返す。 */
    static String export(Context ctx, File src) throws IOException {
        String name = "SoundVoice_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".wav";
        ContentValues v = new ContentValues();
        v.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav");
        v.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/SoundVoice");
        v.put(MediaStore.Audio.Media.IS_PENDING, 1);
        ContentResolver cr = ctx.getContentResolver();
        Uri uri = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
        if (uri == null) throw new IOException("保存先を作れませんでした");
        try (InputStream in = new FileInputStream(src); OutputStream os = cr.openOutputStream(uri)) {
            if (os == null) throw new IOException("保存先を開けませんでした");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        v.clear();
        v.put(MediaStore.Audio.Media.IS_PENDING, 0);
        cr.update(uri, v, null, null);
        src.delete();
        return name;
    }
}
