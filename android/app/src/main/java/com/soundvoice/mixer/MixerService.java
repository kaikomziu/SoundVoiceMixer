package com.soundvoice.mixer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.File;

/** 画面を閉じても音を処理し続けるためのフォアグラウンドサービス。 */
public class MixerService extends Service {
    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";
    static final String ACTION_STOP = "com.soundvoice.mixer.STOP";
    private static final String CHANNEL = "mixer";

    private AudioEngine engine;
    private MediaProjection projection;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (engine != null) return START_NOT_STICKY;

        @SuppressWarnings("deprecation")  // 型付き版は API 33 から
        Intent data = intent == null ? null : intent.getParcelableExtra(EXTRA_DATA);
        // マイク種別は Android 11 から。10 では種別なしでもマイクを使える
        int type = Build.VERSION.SDK_INT >= 30 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0;
        if (data != null) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        if (type == 0) startForeground(1, buildNotification());
        else startForeground(1, buildNotification(), type);

        try {
            if (data != null) {
                MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
                projection = mpm.getMediaProjection(intent.getIntExtra(EXTRA_CODE, 0), data);
                projection.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        stopSelf();
                    }
                }, new Handler(Looper.getMainLooper()));
            }
            engine = new AudioEngine(projection);
            engine.start();
        } catch (Exception e) {
            MainActivity.lastError = "開始できませんでした: " + e.getMessage();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "ミキサー動作中", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, MixerService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("SoundVoice 動作中")
                .setContentText("タップで設定を開く")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "停止", stop).build())
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        if (engine != null) {
            File rec = engine.stopRecording();
            engine.stop();
            engine = null;
            if (rec != null) {
                new Thread(() -> {
                    try {
                        WavWriter.export(getApplicationContext(), rec);
                    } catch (Exception ignored) {
                    }
                }).start();
            }
        }
        if (projection != null) {
            projection.stop();
            projection = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
