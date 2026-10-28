package com.soundvoice.mixer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 1;
    private static final int REQ_PROJECTION = 2;
    private static final int REQ_SOUND = 100;
    private static final int SLOTS = 8;

    static volatile String lastError;
    /** サウンドボードの読み込み済み音声 (画面を作り直しても保持)。 */
    private static final float[][] sounds = new float[SLOTS][];

    private final Params p = Params.INSTANCE;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Runnable> refreshers = new ArrayList<>();
    private SharedPreferences prefs;
    private LinearLayout root;
    private Button startBtn, recBtn;
    private TextView status;
    private final ProgressBar[] meters = new ProgressBar[3];
    private final TextView[] slotNames = new TextView[SLOTS];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        p.load(prefs);

        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad * 2);
        scroll.addView(root);
        setContentView(scroll);
        // ステータスバー/ナビゲーションバーと重ならないように
        scroll.setFitsSystemWindows(true);

        buildUi();
        for (int i = 0; i < SLOTS; i++) {
            if (sounds[i] == null) loadSlot(i, prefs.getString("slot" + i, null), false);
        }
        tick();
    }

    // ================= 画面 =================
    private void buildUi() {
        TextView note = text("※イヤホン推奨（スピーカーだとマイクが拾ってハウリングします）\n"
                + "※Androidの仕様で、Discord等ほかのアプリのマイクに流すことはできません。"
                + "聞く・録音する用途向けです。", 12);
        note.setAlpha(0.7f);
        root.addView(note);

        LinearLayout bar = row();
        startBtn = new Button(this);
        startBtn.setOnClickListener(v -> toggle());
        bar.addView(startBtn, new LinearLayout.LayoutParams(0, -2, 1));
        recBtn = new Button(this);
        recBtn.setOnClickListener(v -> toggleRecording());
        bar.addView(recBtn, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(bar);
        status = text("", 13);
        root.addView(status);

        section("入力（開始前に設定）");
        switchRow("スマホ内の音を混ぜる（ほかのアプリの音）", () -> p.deviceOn, v -> p.deviceOn = v);
        switchRow("マイク", () -> p.micOn, v -> p.micOn = v);

        section("音量");
        meters[0] = slider("スマホの音", 0, 300, () -> p.deviceVol * 100, v -> p.deviceVol = v / 100, "%.0f%%", true);
        checkRow("スマホの音をミュート", () -> p.deviceMute, v -> p.deviceMute = v);
        meters[1] = slider("マイク", 0, 300, () -> p.micVol * 100, v -> p.micVol = v / 100, "%.0f%%", true);
        checkRow("マイクをミュート", () -> p.micMute, v -> p.micMute = v);
        slider("サウンドボード", 0, 200, () -> p.sbVol * 100, v -> p.sbVol = v / 100, "%.0f%%", false);
        meters[2] = slider("マスター", 0, 300, () -> p.masterVol * 100, v -> p.masterVol = v / 100, "%.0f%%", true);
        slider("聞く音量", 0, 200, () -> p.monitorVol * 100, v -> p.monitorVol = v / 100, "%.0f%%", false);

        section("イヤホン/スピーカーで聞く音");
        checkRow("サウンドボード", () -> p.monSb, v -> p.monSb = v);
        checkRow("マイク（自分の声）", () -> p.monMic, v -> p.monMic = v);
        checkRow("スマホ内の音（元の音と二重になります）", () -> p.monDevice, v -> p.monDevice = v);
        TextView recNote = text("録音には全部の音が入ります。保存先: ミュージック/SoundVoice", 12);
        recNote.setAlpha(0.7f);
        root.addView(recNote);

        section("ボイチェン");
        switchRow("ボイチェンON（マイクの声を加工）", () -> p.vcOn, v -> p.vcOn = v);
        spinner("プリセット", Dsp.PRESET_NAMES, () -> p.vcPreset, i -> {
            if (i != p.vcPreset) {
                p.applyPreset(i);
                refreshAll();
            }
        });
        slider("ピッチ(半音)", -12, 12, () -> p.vcPitch, v -> p.vcPitch = v, "%+.0f", false);
        slider("震え", 0, 100, () -> p.vcVibrato, v -> p.vcVibrato = v, "%.0f%%", false);
        slider("ロボット", 0, 100, () -> p.vcRobot, v -> p.vcRobot = v, "%.0f%%", false);
        slider("ロボットの高さ", 20, 200, () -> p.vcRobotHz, v -> p.vcRobotHz = v, "%.0fHz", false);
        slider("リバーブ(響き)", 0, 100, () -> p.vcReverb, v -> p.vcReverb = v, "%.0f%%", false);
        spinner("フィルター", Dsp.FILTERS, () -> p.vcFilter, i -> p.vcFilter = i);

        section("音割れ");
        switchRow("音割れON", () -> p.distOn, v -> p.distOn = v);
        spinner("種類", Dsp.DIST_MODES, () -> p.distMode, i -> p.distMode = i);
        spinner("かける対象", new String[]{"全体", "マイクのみ", "スマホの音のみ"}, () -> p.distTarget, i -> p.distTarget = i);
        slider("強さ", 0, 100, () -> p.distAmount, v -> p.distAmount = v, "%.0f", false);
        Button hold = new Button(this);
        hold.setText("押している間だけ音割れ");
        hold.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) p.distHold = true;
            else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) p.distHold = false;
            return false;
        });
        root.addView(hold);

        section("ノイズゲート（マイク）");
        switchRow("ノイズゲートON", () -> p.gateOn, v -> p.gateOn = v);
        slider("しきい値", -80, 0, () -> p.gateDb, v -> p.gateDb = v, "%.0fdB", false);

        section("エコー（マイク）");
        switchRow("エコーON", () -> p.echoOn, v -> p.echoOn = v);
        slider("ディレイ", 50, 1000, () -> p.echoMs, v -> p.echoMs = v, "%.0fms", false);
        slider("繰り返し", 0, 90, () -> p.echoFb * 100, v -> p.echoFb = v / 100, "%.0f%%", false);
        slider("エコー量", 0, 100, () -> p.echoMix * 100, v -> p.echoMix = v / 100, "%.0f%%", false);

        section("サウンドボード");
        for (int i = 0; i < SLOTS; i++) {
            final int slot = i;
            LinearLayout r = row();
            Button play = new Button(this);
            play.setText("▶ " + (i + 1));
            play.setOnClickListener(v -> playSlot(slot));
            r.addView(play, new LinearLayout.LayoutParams(dp(80), -2));
            TextView name = text("(未設定)", 14);
            name.setSingleLine(true);
            name.setPadding(dp(8), 0, dp(8), 0);
            slotNames[i] = name;
            r.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            Button pick = new Button(this);
            pick.setText("選択");
            pick.setOnClickListener(v -> pickSound(slot));
            r.addView(pick);
            root.addView(r);
            String saved = prefs.getString("slotName" + i, null);
            if (saved != null) name.setText(saved);
        }
        Button stopAll = new Button(this);
        stopAll.setText("■ サウンドを全部止める");
        stopAll.setOnClickListener(v -> {
            AudioEngine e = AudioEngine.current;
            if (e != null) e.stopSounds();
        });
        root.addView(stopAll);
    }

    // ---- 部品 ----
    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        return t;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private void section(String title) {
        TextView t = text(title, 18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(20), 0, dp(4));
        root.addView(t);
    }

    private void switchRow(String label, Supplier<Boolean> get, Consumer<Boolean> set) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextSize(15);
        s.setPadding(0, dp(6), 0, dp(6));
        s.setOnCheckedChangeListener((b, c) -> set.accept(c));
        root.addView(s);
        Runnable r = () -> s.setChecked(get.get());
        r.run();
        refreshers.add(r);
    }

    private void checkRow(String label, Supplier<Boolean> get, Consumer<Boolean> set) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setOnCheckedChangeListener((b, v) -> set.accept(v));
        root.addView(c);
        Runnable r = () -> c.setChecked(get.get());
        r.run();
        refreshers.add(r);
    }

    private ProgressBar slider(String label, int min, int max, Supplier<Float> get, Consumer<Float> set,
                               String fmt, boolean meter) {
        LinearLayout head = row();
        TextView name = text(label, 14);
        head.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = text("", 14);
        head.addView(value);
        root.addView(head);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                float v = progress + min;
                value.setText(String.format(Locale.US, fmt, v));
                if (fromUser) set.accept(v);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {}

            @Override
            public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(sb);
        Runnable r = () -> {
            sb.setProgress(Math.round(get.get()) - min);
            value.setText(String.format(Locale.US, fmt, get.get()));
        };
        r.run();
        refreshers.add(r);
        if (!meter) return null;
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(1000);
        root.addView(pb, new LinearLayout.LayoutParams(-1, dp(6)));
        return pb;
    }

    private void spinner(String label, String[] items, Supplier<Integer> get, Consumer<Integer> set) {
        LinearLayout r = row();
        r.addView(text(label, 14), new LinearLayout.LayoutParams(dp(110), -2));
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items));
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                set.accept(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        r.addView(sp, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(r);
        Runnable rf = () -> sp.setSelection(get.get());
        rf.run();
        refreshers.add(rf);
    }

    private void refreshAll() {
        for (Runnable r : refreshers) r.run();
    }

    // ================= 開始/停止 =================
    private boolean running() {
        AudioEngine e = AudioEngine.current;
        return e != null && e.isRunning();
    }

    private void toggle() {
        if (running()) {
            stopService(new Intent(this, MixerService.class));
            return;
        }
        List<String> need = new ArrayList<>();
        need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS);
        List<String> missing = new ArrayList<>();
        for (String perm : need) {
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) missing.add(perm);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
            return;
        }
        if (p.deviceOn) {
            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            // Android 14 以降は「1つのアプリ」が既定になるので、スマホ全体の音を拾えるよう画面全体を指定する
            Intent capture = Build.VERSION.SDK_INT >= 34
                    ? mpm.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
                    : mpm.createScreenCaptureIntent();
            startActivityForResult(capture, REQ_PROJECTION);
        } else {
            startMixer(0, null);
        }
    }

    private void startMixer(int code, Intent data) {
        lastError = null;
        Intent i = new Intent(this, MixerService.class);
        if (data != null) {
            i.putExtra(MixerService.EXTRA_CODE, code);
            i.putExtra(MixerService.EXTRA_DATA, data);
        }
        startForegroundService(i);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQ_PERMS) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            toggle();
        } else {
            toast("マイクの許可が必要です");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                startMixer(resultCode, data);
            } else {
                toast("スマホ内の音なしで開始します");
                startMixer(0, null);
            }
        } else if (requestCode >= REQ_SOUND && requestCode < REQ_SOUND + SLOTS
                && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
            }
            loadSlot(requestCode - REQ_SOUND, uri.toString(), true);
        }
    }

    // ================= 録音 =================
    private void toggleRecording() {
        AudioEngine e = AudioEngine.current;
        if (e == null || !e.isRunning()) {
            toast("先に「開始」してください");
            return;
        }
        if (!e.isRecording()) {
            try {
                e.startRecording(new File(getCacheDir(), "rec_" + System.currentTimeMillis() + ".wav"));
                toast("録音開始");
            } catch (Exception ex) {
                toast("録音できません: " + ex.getMessage());
            }
            return;
        }
        File f = e.stopRecording();
        if (f == null) return;
        new Thread(() -> {
            String msg;
            try {
                msg = "保存しました: ミュージック/SoundVoice/" + WavWriter.export(getApplicationContext(), f);
            } catch (Exception ex) {
                msg = "保存に失敗: " + ex.getMessage();
            }
            String m = msg;
            handler.post(() -> toast(m));
        }).start();
    }

    // ================= サウンドボード =================
    private void pickSound(int slot) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        startActivityForResult(i, REQ_SOUND + slot);
    }

    private void loadSlot(int slot, String uriString, boolean announce) {
        if (uriString == null) return;
        Uri uri = Uri.parse(uriString);
        String name = displayName(uri);
        slotNames[slot].setText("読み込み中…");
        new Thread(() -> {
            try {
                float[] data = SoundLoader.load(getApplicationContext(), uri);
                sounds[slot] = data;
                prefs.edit().putString("slot" + slot, uriString).putString("slotName" + slot, name).apply();
                handler.post(() -> {
                    slotNames[slot].setText(name);
                    if (announce) toast(name + " を読み込みました");
                });
            } catch (Exception ex) {
                handler.post(() -> {
                    slotNames[slot].setText("(読み込み失敗)");
                    if (announce) toast("読み込めませんでした: " + ex.getMessage());
                });
            }
        }).start();
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        }
        return uri.getLastPathSegment();
    }

    private void playSlot(int slot) {
        AudioEngine e = AudioEngine.current;
        if (e == null || !e.isRunning()) {
            toast("先に「開始」してください");
        } else if (sounds[slot] == null) {
            toast("「選択」で音声ファイルを選んでください");
        } else {
            e.play(sounds[slot]);
        }
    }

    // ================= 表示更新 =================
    private void tick() {
        AudioEngine e = AudioEngine.current;
        boolean run = e != null && e.isRunning();
        startBtn.setText(run ? "■ 停止" : "▶ 開始");
        recBtn.setText(e != null && e.isRecording() ? "■ 録音停止" : "● 録音");
        String err = e != null && e.error != null ? e.error : lastError;
        if (err != null) {
            status.setText(err);
        } else if (run) {
            status.setText(e.hasDeviceCapture() ? "動作中（マイク＋スマホ内の音）" : "動作中");
        } else {
            status.setText("停止中");
        }
        for (int i = 0; i < 3; i++) {
            if (meters[i] != null) meters[i].setProgress(run ? (int) (Math.min(1f, e.levels[i]) * 1000) : 0);
        }
        handler.postDelayed(this::tick, 50);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        p.save(prefs);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
