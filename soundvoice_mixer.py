"""SoundVoice Mixer
デスクトップ音(WASAPIループバック)と指定マイクをミックスし、
仮想オーディオケーブル(例: VB-CABLE の "CABLE Input")へ出力する。
録音側デバイス名を "soundvoice Output" にしておけば、他アプリからは
"soundvoice Output" という仮想マイクとして見える。

機能: 音量調整 / 音割れ / ノイズゲート / エコー / サウンドボード /
      モニター(自分で聞く) / グローバルホットキー
"""
import collections
import ctypes
import json
import os
import sys
import threading
import tkinter as tk
import wave
from tkinter import filedialog, messagebox, ttk

import numpy as np
import pyaudiowpatch as pyaudio

try:
    import soundfile
except ImportError:
    soundfile = None

try:
    from scipy import signal
except ImportError:
    signal = None

# exe化したときは一時フォルダではなく exe の隣に設定を保存する
APP_DIR = os.path.dirname(sys.executable if getattr(sys, "frozen", False) else os.path.abspath(__file__))
CONFIG_PATH = os.path.join(APP_DIR, "settings.json")
BLOCK_MS = 10          # ストリームのブロック長
PRIME_MS = 30          # アンダーラン後に溜めてから再生を再開する量
MAX_BUFFER_MS = 120    # これ以上溜まったら古いものを捨てる(遅延・ドリフト対策)
OUTPUT_NAME_HINTS = ("soundvoice", "cable input")
NONE = "(なし)"
SOUNDBOARD_SLOTS = 8

# ホットキーに使えるキー (名前 → 仮想キーコード)
HOTKEYS = {NONE: 0}
HOTKEYS.update({f"F{i}": 0x6F + i for i in range(1, 25)})
HOTKEYS.update({f"テンキー{i}": 0x60 + i for i in range(10)})
HOTKEYS.update({"Pause": 0x13, "ScrollLock": 0x91, "Insert": 0x2D, "Home": 0x24,
                "End": 0x23, "PageUp": 0x21, "PageDown": 0x22,
                "マウス戻る": 0x05, "マウス進む": 0x06, "マウス中": 0x04})


def key_down(vk):
    return vk and bool(ctypes.windll.user32.GetAsyncKeyState(vk) & 0x8000)


class Resampler:
    """チャンク境界をまたいでも連続になる線形補間リサンプラ (ステレオ float32)。"""

    def __init__(self, src_rate, dst_rate):
        self.ratio = src_rate / dst_rate
        self.pos = 1.0
        self.prev = np.zeros((1, 2), np.float32)

    def process(self, x):
        if self.ratio == 1.0:
            return x
        data = np.vstack([self.prev, x])
        last = len(data) - 1
        idx = np.arange(self.pos, last, self.ratio)
        if len(idx):
            i0 = idx.astype(np.int64)
            frac = (idx - i0)[:, None].astype(np.float32)
            out = data[i0] * (1 - frac) + data[i0 + 1] * frac
            self.pos = idx[-1] + self.ratio - last
        else:
            out = np.zeros((0, 2), np.float32)
            self.pos -= last
        self.prev = data[-1:]
        return out


class FrameBuffer:
    """スレッド間でステレオフレームを受け渡すジッタバッファ。"""

    def __init__(self, rate):
        self.chunks = collections.deque()
        self.count = 0
        self.primed = False
        self.prime = int(rate * PRIME_MS / 1000)
        self.max_frames = int(rate * MAX_BUFFER_MS / 1000)
        self.lock = threading.Lock()

    def push(self, x):
        with self.lock:
            self.chunks.append(x)
            self.count += len(x)
            while self.count > self.max_frames and len(self.chunks) > 1:
                self.count -= len(self.chunks.popleft())

    def read(self, n):
        out = np.zeros((n, 2), np.float32)
        with self.lock:
            if not self.primed:
                if self.count < self.prime:
                    return out
                self.primed = True
            filled = 0
            while filled < n and self.chunks:
                c = self.chunks[0]
                take = min(n - filled, len(c))
                out[filled:filled + take] = c[:take]
                if take == len(c):
                    self.chunks.popleft()
                else:
                    self.chunks[0] = c[take:]
                filled += take
                self.count -= take
            if filled < n:
                self.primed = False
        return out


def to_device_channels(x, ch):
    if ch == 2:
        return np.ascontiguousarray(x)
    if ch == 1:
        return np.ascontiguousarray(x.mean(axis=1, keepdims=True))
    out = np.zeros((len(x), ch), np.float32)
    out[:, :2] = x
    return out


class Source:
    """入力デバイスを開き、出力レートのステレオに変換してバッファへ溜める。"""

    def __init__(self, pa, dev, out_rate, loopback):
        self.rate = int(dev["defaultSampleRate"])
        ch = int(dev["maxInputChannels"])
        self.ch = ch if loopback else max(1, min(2, ch))
        self.resampler = Resampler(self.rate, out_rate)
        self.buffer = FrameBuffer(out_rate)
        self.stream = pa.open(
            format=pyaudio.paFloat32,
            channels=self.ch,
            rate=self.rate,
            input=True,
            input_device_index=dev["index"],
            frames_per_buffer=int(self.rate * BLOCK_MS / 1000),
            stream_callback=self._callback,
        )

    def _callback(self, in_data, frame_count, time_info, status):
        x = np.frombuffer(in_data, np.float32).reshape(-1, self.ch)
        x = np.repeat(x, 2, axis=1) if self.ch == 1 else x[:, :2]
        self.buffer.push(self.resampler.process(np.ascontiguousarray(x, np.float32)))
        return (None, pyaudio.paContinue)

    def read(self, n):
        return self.buffer.read(n)

    def close(self):
        try:
            self.stream.stop_stream()
            self.stream.close()
        except Exception:
            pass


class Monitor:
    """ミックス結果を別デバイス(ヘッドホン等)でも鳴らす。"""

    def __init__(self, pa, dev, mix_rate, params):
        self.rate = int(dev["defaultSampleRate"])
        self.ch = int(dev["maxOutputChannels"])
        self.params = params
        self.resampler = Resampler(mix_rate, self.rate)
        self.buffer = FrameBuffer(self.rate)
        self.stream = pa.open(
            format=pyaudio.paFloat32,
            channels=self.ch,
            rate=self.rate,
            output=True,
            output_device_index=dev["index"],
            frames_per_buffer=int(self.rate * BLOCK_MS / 1000),
            stream_callback=self._callback,
        )

    def push(self, x):
        self.buffer.push(self.resampler.process(x))

    def _callback(self, in_data, frame_count, time_info, status):
        x = self.buffer.read(frame_count) * self.params["monitor_vol"]
        return (to_device_channels(x, self.ch).tobytes(), pyaudio.paContinue)

    def close(self):
        try:
            self.stream.stop_stream()
            self.stream.close()
        except Exception:
            pass


class NoiseGate:
    """しきい値より小さい音(環境ノイズ)をカットする。"""

    def __init__(self, rate):
        self.rate = rate
        self.gain = 1.0
        self.hold = 0

    def process(self, x, threshold_db):
        rms = float(np.sqrt(np.mean(x * x))) + 1e-9
        if 20 * np.log10(rms) > threshold_db:
            self.hold = int(self.rate * 0.15)
        else:
            self.hold -= len(x)
        target = 1.0 if self.hold > 0 else 0.0
        new = self.gain + (target - self.gain) * (0.6 if target > self.gain else 0.15)
        ramp = np.linspace(self.gain, new, len(x), dtype=np.float32)[:, None]
        self.gain = new
        return x * ramp


class Echo:
    """フィードバック付きディレイ(最大2秒)。"""

    def __init__(self, rate):
        self.rate = rate
        self.buf = np.zeros((rate * 2, 2), np.float32)
        self.pos = 0

    def reset(self):
        self.buf[:] = 0

    def process(self, x, delay_ms, feedback, mix):
        n, size = len(x), len(self.buf)
        delay = max(n, int(delay_ms / 1000 * self.rate))
        delayed = self.buf[np.arange(self.pos - delay, self.pos - delay + n) % size]
        self.buf[np.arange(self.pos, self.pos + n) % size] = x + delayed * feedback
        self.pos = (self.pos + n) % size
        return x + delayed * mix


class _Comb:
    """y[n] = x[n] + g*y[n-D]"""

    def __init__(self, delay, g):
        self.buf = np.zeros(delay, np.float32)
        self.idx = 0
        self.g = g

    def process(self, x):
        out = np.empty_like(x)
        d = len(self.buf)
        for s in range(0, len(x), d):           # D サンプル以内なら一括計算できる
            c = x[s:s + d]
            i = (self.idx + np.arange(len(c))) % d
            y = c + self.g * self.buf[i]
            self.buf[i] = y
            out[s:s + d] = y
            self.idx = (self.idx + len(c)) % d
        return out


class _Allpass(_Comb):
    def process(self, x):
        out = np.empty_like(x)
        d = len(self.buf)
        for s in range(0, len(x), d):
            c = x[s:s + d]
            i = (self.idx + np.arange(len(c))) % d
            delayed = self.buf[i]
            w = c + self.g * delayed
            self.buf[i] = w
            out[s:s + d] = delayed - self.g * w
            self.idx = (self.idx + len(c)) % d
        return out


class Reverb:
    """シュレーダー型リバーブ(モノラル)。"""

    def __init__(self, rate):
        self.combs = [_Comb(int(rate * ms / 1000), 0.84) for ms in (29.7, 37.1, 41.1, 43.7)]
        self.allpasses = [_Allpass(int(rate * ms / 1000), 0.7) for ms in (5.0, 1.7)]

    def process(self, x):
        wet = sum(c.process(x) for c in self.combs) * 0.25
        for a in self.allpasses:
            wet = a.process(wet)
        return wet


# ボイチェンのフィルター: (種類, 周波数, 歪ませ量)
VOICE_FILTERS = {
    "なし": None,
    "電話": ("bandpass", (400, 3000), 1.5),
    "ラジオ": ("bandpass", (600, 4000), 3.0),
    "メガホン": ("bandpass", (500, 2500), 5.0),
    "こもり声": ("lowpass", 700, 1.0),
    "シャカシャカ": ("highpass", 2000, 1.0),
}

# プリセット: ピッチ(半音), 震え(%), ロボット(%), ロボット周波数(Hz), フィルター, リバーブ(%)
VOICE_PRESETS = {
    "ノーマル": (0, 0, 0, 50, "なし", 0),
    "女声風(高め)": (5, 0, 0, 50, "なし", 0),
    "男声風(低め)": (-5, 0, 0, 50, "なし", 0),
    "子ども": (8, 0, 0, 50, "なし", 0),
    "ヘリウム": (12, 0, 0, 50, "なし", 0),
    "魔王": (-10, 0, 0, 50, "なし", 35),
    "ロボット": (0, 0, 100, 60, "なし", 10),
    "宇宙人": (7, 40, 60, 30, "なし", 20),
    "おばけ": (-2, 100, 0, 50, "なし", 60),
    "電話": (0, 0, 0, 50, "電話", 0),
    "ラジオ": (0, 0, 0, 50, "ラジオ", 0),
    "メガホン": (0, 0, 0, 50, "メガホン", 15),
    "洞窟": (0, 0, 0, 50, "なし", 85),
    "壁の向こう": (0, 0, 0, 50, "こもり声", 20),
}


class VoiceChanger:
    """ピッチ変更 + 震え + ロボット + フィルター + リバーブ (マイク用、モノラル処理)。"""

    WINDOW_MS = 40

    def __init__(self, rate):
        self.rate = rate
        self.window = int(rate * self.WINDOW_MS / 1000)
        self.size = 1 << 16
        self.buf = np.zeros(self.size, np.float32)
        self.pos = 0
        self.phase = 0.0
        self.lfo = 0.0
        self.ring = 0.0
        self.reverb = Reverb(rate)
        self.filter_name = "なし"
        self.sos = None
        self.zi = None

    def _pitch(self, x, semitones, vibrato):
        """2つの読み出しヘッドをクロスフェードするディレイライン型ピッチシフタ。"""
        n, size, w = len(x), self.size, self.window
        idx = self.pos + np.arange(n)
        self.buf[idx % size] = x
        self.pos = (self.pos + n) % size
        if semitones == 0 and vibrato == 0:
            return x
        t = self.lfo + 2 * np.pi * 6.0 / self.rate * np.arange(1, n + 1)
        self.lfo = float(t[-1] % (2 * np.pi))
        ratio = 2.0 ** ((semitones + vibrato * np.sin(t)) / 12)
        phase = (self.phase + np.cumsum((1 - ratio) / w)) % 1.0
        self.phase = float(phase[-1])
        out = np.zeros(n, np.float32)
        for ph in (phase, (phase + 0.5) % 1.0):
            rp = idx - ph * w
            i0 = np.floor(rp).astype(np.int64)
            fr = (rp - i0).astype(np.float32)
            s = self.buf[i0 % size] * (1 - fr) + self.buf[(i0 + 1) % size] * fr
            out += s * (np.sin(np.pi * ph) ** 2).astype(np.float32)
        return out

    def _filter(self, x, name):
        spec = VOICE_FILTERS.get(name)
        if not spec or signal is None:
            return x
        if name != self.filter_name or self.sos is None:
            kind, freq, _ = spec
            self.sos = signal.butter(4, freq, btype=kind, fs=self.rate, output="sos")
            self.zi = np.zeros((self.sos.shape[0], 2))
            self.filter_name = name
        y, self.zi = signal.sosfilt(self.sos, x, zi=self.zi)
        drive = spec[2]
        if drive > 1:
            y = np.tanh(y * drive) / np.tanh(drive) * 0.7
        return y.astype(np.float32)

    def process(self, stereo, p):
        x = stereo.mean(axis=1).astype(np.float32)
        x = self._pitch(x, p["vc_pitch"], p["vc_vibrato"] / 100)
        if p["vc_robot"] > 0:
            n = len(x)
            t = self.ring + 2 * np.pi * p["vc_robot_hz"] / self.rate * np.arange(1, n + 1)
            self.ring = float(t[-1] % (2 * np.pi))
            a = p["vc_robot"] / 100
            x = x * (1 - a) + x * np.sin(t).astype(np.float32) * a * 1.4
        x = self._filter(x, p["vc_filter"])
        if p["vc_reverb"] > 0:
            a = p["vc_reverb"] / 100
            x = x * (1 - 0.4 * a) + self.reverb.process(x) * a
        return np.repeat(x[:, None], 2, axis=1)


def distort(x, mode, amount):
    """音割れエフェクト。amount は 0〜100。"""
    if mode == "ビットクラッシュ":
        drive = 10 ** (amount * 0.2 / 20)          # 最大 +20dB
        bits = 16 - amount / 100 * 13               # 16bit → 3bit
        step = 2.0 / (2 ** bits)
        y = np.round(np.clip(x * drive, -1, 1) / step) * step
        hold = 1 + int(amount / 100 * 15)           # サンプルレート低下
        if hold > 1:
            y = np.repeat(y[::hold], hold, axis=0)[:len(x)]
        return y
    drive = 10 ** (amount * 0.45 / 20)              # 最大 +45dB
    if mode == "ソフト":
        return np.tanh(x * drive)
    return np.clip(x * drive, -0.8, 0.8) * 1.25     # ハードクリップ(ガチ音割れ)


def load_audio(path):
    """音声ファイルを (ステレオ float32 配列, サンプルレート) で読み込む。"""
    if soundfile is not None:
        data, rate = soundfile.read(path, dtype="float32", always_2d=True)
    else:
        with wave.open(path, "rb") as w:
            rate, ch, width = w.getframerate(), w.getnchannels(), w.getsampwidth()
            raw = w.readframes(w.getnframes())
        if width == 1:
            data = (np.frombuffer(raw, np.uint8).astype(np.float32) - 128) / 128
        elif width == 2:
            data = np.frombuffer(raw, "<i2").astype(np.float32) / 32768
        elif width == 3:
            b = np.frombuffer(raw, np.uint8).reshape(-1, 3).astype(np.int32)
            v = b[:, 0] | (b[:, 1] << 8) | (b[:, 2] << 16)
            data = np.where(v >= 1 << 23, v - (1 << 24), v).astype(np.float32) / (1 << 23)
        else:
            data = np.frombuffer(raw, "<i4").astype(np.float32) / (1 << 31)
        data = data.reshape(-1, ch)
    data = np.repeat(data, 2, axis=1) if data.shape[1] == 1 else data[:, :2]
    return np.ascontiguousarray(data, np.float32), int(rate)


def resample_whole(data, src, dst):
    if src == dst:
        return data
    n = int(len(data) * dst / src)
    t = np.linspace(0, len(data) - 1, n)
    base = np.arange(len(data))
    return np.stack([np.interp(t, base, data[:, c]) for c in range(2)], 1).astype(np.float32)


class Mixer:
    def __init__(self):
        self.pa = pyaudio.PyAudio()
        self.wasapi = self.pa.get_host_api_info_by_type(pyaudio.paWASAPI)["index"]
        self.desktop = None
        self.mic = None
        self.monitor = None
        self.out_stream = None
        self.rate = 48000
        self.voices = []
        self.voice_lock = threading.Lock()
        self.params = {
            "desktop_vol": 1.0, "mic_vol": 1.0, "master_vol": 1.0, "sb_vol": 1.0,
            "monitor_vol": 1.0, "monitor_sb": True, "monitor_mic": True, "monitor_desktop": False,
            "desktop_mute": False, "mic_mute": False,
            "dist_on": False, "dist_hold": False, "dist_mode": "ハードクリップ",
            "dist_amount": 50.0, "dist_target": "全体",
            "gate_on": False, "gate_db": -45.0,
            "echo_on": False, "echo_ms": 250.0, "echo_fb": 0.4, "echo_mix": 0.5,
            "vc_on": False, "vc_pitch": 0.0, "vc_vibrato": 0.0, "vc_robot": 0.0,
            "vc_robot_hz": 50.0, "vc_filter": "なし", "vc_reverb": 0.0,
        }
        self.levels = {"desktop": 0.0, "mic": 0.0, "out": 0.0}

    # ---- デバイス列挙 ----
    def _wasapi_devices(self):
        for i in range(self.pa.get_device_count()):
            d = self.pa.get_device_info_by_index(i)
            if d["hostApi"] == self.wasapi:
                yield d

    def loopback_devices(self):
        return list(self.pa.get_loopback_device_info_generator())

    def mic_devices(self):
        return [d for d in self._wasapi_devices()
                if d["maxInputChannels"] > 0 and not d.get("isLoopbackDevice")]

    def output_devices(self):
        return [d for d in self._wasapi_devices() if d["maxOutputChannels"] > 0]

    def default_loopback(self):
        try:
            return self.pa.get_default_wasapi_loopback()
        except Exception:
            return None

    # ---- 開始/停止 ----
    def start(self, desktop_dev, mic_dev, out_dev, monitor_dev=None):
        self.stop()
        self.rate = int(out_dev["defaultSampleRate"])
        self.out_ch = int(out_dev["maxOutputChannels"])
        self.gate = NoiseGate(self.rate)
        self.echo = Echo(self.rate)
        self.echo_was_on = False
        self.voice = VoiceChanger(self.rate)
        try:
            if desktop_dev:
                self.desktop = Source(self.pa, desktop_dev, self.rate, loopback=True)
            if mic_dev:
                self.mic = Source(self.pa, mic_dev, self.rate, loopback=False)
            if monitor_dev:
                self.monitor = Monitor(self.pa, monitor_dev, self.rate, self.params)
            self.out_stream = self.pa.open(
                format=pyaudio.paFloat32,
                channels=self.out_ch,
                rate=self.rate,
                output=True,
                output_device_index=out_dev["index"],
                frames_per_buffer=int(self.rate * BLOCK_MS / 1000),
                stream_callback=self._out_callback,
            )
        except Exception:
            self.stop()
            raise

    def stop(self):
        if self.out_stream:
            try:
                self.out_stream.stop_stream()
                self.out_stream.close()
            except Exception:
                pass
            self.out_stream = None
        for s in (self.desktop, self.mic, self.monitor):
            if s:
                s.close()
        self.desktop = self.mic = self.monitor = None
        with self.voice_lock:
            self.voices = []
        self.levels = dict.fromkeys(self.levels, 0.0)

    @property
    def running(self):
        return self.out_stream is not None

    # ---- サウンドボード ----
    def play(self, data):
        with self.voice_lock:
            self.voices.append([data, 0])

    def stop_sounds(self):
        with self.voice_lock:
            self.voices = []

    def _soundboard(self, n):
        out = np.zeros((n, 2), np.float32)
        with self.voice_lock:
            alive = []
            for v in self.voices:
                data, pos = v
                chunk = data[pos:pos + n]
                out[:len(chunk)] += chunk
                v[1] = pos + n
                if v[1] < len(data):
                    alive.append(v)
            self.voices = alive
        return out

    # ---- ミックス ----
    def _distorting(self):
        p = self.params
        return p["dist_on"] or p["dist_hold"]

    def _combine(self, d, m, sb):
        p = self.params
        if self._distorting() and p["dist_target"] == "デスクトップのみ":
            d = distort(d, p["dist_mode"], p["dist_amount"])
        mix = d + m + sb
        if self._distorting() and p["dist_target"] == "全体":
            mix = distort(mix, p["dist_mode"], p["dist_amount"])
        return np.clip(mix * p["master_vol"], -1.0, 1.0).astype(np.float32)

    def _out_callback(self, in_data, frame_count, time_info, status):
        p = self.params
        n = frame_count
        d = self.desktop.read(n) if self.desktop else np.zeros((n, 2), np.float32)
        m = self.mic.read(n) if self.mic else np.zeros((n, 2), np.float32)
        d *= 0.0 if p["desktop_mute"] else p["desktop_vol"]

        if p["gate_on"]:
            m = self.gate.process(m, p["gate_db"])
        if p["vc_on"]:
            m = self.voice.process(m, p)
        m *=0.0 if p["mic_mute"] else p["mic_vol"]
        if p["echo_on"]:
            if not self.echo_was_on:
                self.echo.reset()
            m = self.echo.process(m, p["echo_ms"], p["echo_fb"], p["echo_mix"])
        self.echo_was_on = p["echo_on"]
        if self._distorting() and p["dist_target"] == "マイクのみ":
            m = distort(m, p["dist_mode"], p["dist_amount"])

        sb = self._soundboard(n) * p["sb_vol"]
        mix = self._combine(d, m, sb)

        if self.monitor:
            if p["monitor_desktop"] and p["monitor_mic"] and p["monitor_sb"]:
                self.monitor.push(mix)
            else:
                z = np.zeros_like(d)
                self.monitor.push(self._combine(d if p["monitor_desktop"] else z,
                                                m if p["monitor_mic"] else z,
                                                sb if p["monitor_sb"] else z))

        self.levels["desktop"] = max(self.levels["desktop"] * 0.85, float(np.abs(d).max(initial=0)))
        self.levels["mic"] = max(self.levels["mic"] * 0.85, float(np.abs(m).max(initial=0)))
        self.levels["out"] = max(self.levels["out"] * 0.85, float(np.abs(mix).max(initial=0)))
        return (to_device_channels(mix, self.out_ch).tobytes(), pyaudio.paContinue)

    def close(self):
        self.stop()
        self.pa.terminate()


class App:
    def __init__(self, root):
        self.root = root
        self.mixer = Mixer()
        self.cfg = self._load_config()
        self.vars = {}
        self.labels = {}
        self.meters = {}
        root.title("SoundVoice Mixer")
        root.resizable(False, False)
        # exe では同梱したアイコン、スクリプト実行時は隣の icon.ico を使う
        icon = os.path.join(getattr(sys, "_MEIPASS", APP_DIR), "icon.ico")
        if os.path.exists(icon):
            root.iconbitmap(icon)
        root.protocol("WM_DELETE_WINDOW", self.on_close)

        self.loop_devs = self.mixer.loopback_devices()
        self.mic_devs = self.mixer.mic_devices()
        self.out_devs = self.mixer.output_devices()

        outer = ttk.Frame(root, padding=10)
        outer.grid()
        nb = ttk.Notebook(outer)
        nb.grid(row=0, column=0, sticky="ew")
        self._build_mixer_tab(nb)
        self._build_voice_tab(nb)
        self._build_effects_tab(nb)
        self._build_soundboard_tab(nb)
        self._build_hotkey_tab(nb)

        bar = ttk.Frame(outer)
        bar.grid(row=1, column=0, sticky="ew", pady=(8, 0))
        self.start_btn = ttk.Button(bar, text="▶ 開始", command=self.toggle)
        self.start_btn.pack(side="left")
        self.status = ttk.Label(bar, text="停止中")
        self.status.pack(side="left", padx=10)

        for key in ("desktop_dev", "mic_dev", "out_dev", "monitor_dev"):
            self.vars[key].trace_add("write", lambda *_: self._restart_if_running())

        self.key_state = {}
        self._apply()
        self._tick()
        self._poll_keys()
        if self.cfg.get("autostart"):
            self.toggle()

    # ---- UI部品 ----
    def _var(self, key, default, kind=tk.DoubleVar):
        var = kind(value=self.cfg.get(key, default))
        self.vars[key] = var
        return var

    def _combo(self, parent, row, label, key, values, width=52):
        ttk.Label(parent, text=label).grid(row=row, column=0, sticky="w", pady=2)
        var = self._var(key, "", tk.StringVar)
        cb = ttk.Combobox(parent, textvariable=var, values=values, state="readonly", width=width)
        cb.grid(row=row, column=1, columnspan=3, sticky="w", padx=6, pady=2)
        var.trace_add("write", lambda *_: self._apply())
        return cb

    def _check(self, parent, text, key, row, col, default=False):
        ttk.Checkbutton(parent, text=text, variable=self._var(key, default, tk.BooleanVar),
                        command=self._apply).grid(row=row, column=col, sticky="w")

    def _slider(self, parent, row, label, key, lo, hi, default, fmt, scale=1.0, meter=None):
        """scale: 保存値 = スライダー値 * scale (音量は % 表示なので 0.01)。"""
        ttk.Label(parent, text=label, width=12).grid(row=row, column=0, sticky="w")
        var = tk.DoubleVar(value=self.cfg.get(key, default) / scale)
        self.vars[key] = var
        self.vars[key + "_scale"] = scale
        ttk.Scale(parent, from_=lo, to=hi, variable=var, length=260,
                  command=lambda *_: self._apply()).grid(row=row, column=1, padx=4)
        lab = ttk.Label(parent, width=7)
        lab.grid(row=row, column=2)
        self.labels[key] = (lab, fmt)
        if meter:
            pb = ttk.Progressbar(parent, length=100, maximum=1.0)
            pb.grid(row=row, column=3, padx=4)
            self.meters[meter] = pb

    # ---- タブ ----
    def _build_mixer_tab(self, nb):
        tab = ttk.Frame(nb, padding=8)
        nb.add(tab, text="ミキサー")

        dev = ttk.LabelFrame(tab, text="デバイス", padding=8)
        dev.grid(row=0, column=0, sticky="ew")
        out_names = [d["name"] for d in self.out_devs]
        self._combo(dev, 0, "デスクトップ音", "desktop_dev", [NONE] + [d["name"] for d in self.loop_devs])
        self._combo(dev, 1, "マイク", "mic_dev", [NONE] + [d["name"] for d in self.mic_devs])
        self._combo(dev, 2, "出力先(仮想ケーブル)", "out_dev", out_names)
        self._combo(dev, 3, "モニター(自分で聞く)", "monitor_dev", [NONE] + out_names)
        self._select_defaults()

        vol = ttk.LabelFrame(tab, text="音量", padding=8)
        vol.grid(row=1, column=0, sticky="ew", pady=(8, 0))
        self._slider(vol, 0, "デスクトップ", "desktop_vol", 0, 300, 1.0, "{:.0f}%", 0.01, "desktop")
        self._check(vol, "ミュート", "desktop_mute", 0, 4)
        self._slider(vol, 1, "マイク", "mic_vol", 0, 300, 1.0, "{:.0f}%", 0.01, "mic")
        self._check(vol, "ミュート", "mic_mute", 1, 4)
        self._slider(vol, 2, "マスター", "master_vol", 0, 300, 1.0, "{:.0f}%", 0.01, "out")
        self._slider(vol, 3, "モニター", "monitor_vol", 0, 200, 1.0, "{:.0f}%", 0.01)

        opt = ttk.Frame(tab)
        opt.grid(row=2, column=0, sticky="w", pady=(6, 0))
        ttk.Label(opt, text="モニターで聞く音:").grid(row=0, column=0, sticky="w")
        self._check(opt, "サウンドボード", "monitor_sb", 0, 1, True)
        self._check(opt, "マイク(自分の声)", "monitor_mic", 0, 2, True)
        # 旧設定 monitor_no_desktop からの引き継ぎ
        self._check(opt, "デスクトップ音", "monitor_desktop", 0, 3, not self.cfg.get("monitor_no_desktop", True))
        self._check(opt, "起動時に自動で開始", "autostart", 1, 0)

    def _build_voice_tab(self, nb):
        tab = ttk.Frame(nb, padding=8)
        nb.add(tab, text="ボイチェン")
        self._check(tab, "ボイチェンON(マイクの声を加工)", "vc_on", 0, 0)

        pre = ttk.LabelFrame(tab, text="プリセット", padding=8)
        pre.grid(row=1, column=0, sticky="ew", pady=(6, 0))
        self.vc_preset = tk.StringVar(value=self.cfg.get("vc_preset", "ノーマル"))
        for i, name in enumerate(VOICE_PRESETS):
            ttk.Radiobutton(pre, text=name, value=name, variable=self.vc_preset, width=13,
                            command=lambda n=name: self._apply_preset(n)).grid(
                row=i // 4, column=i % 4, sticky="w", padx=2, pady=1)

        adj = ttk.LabelFrame(tab, text="細かく調整", padding=8)
        adj.grid(row=2, column=0, sticky="ew", pady=(8, 0))
        self._slider(adj, 0, "ピッチ", "vc_pitch", -12, 12, 0.0, "{:+.1f}")
        ttk.Label(adj, text="半音").grid(row=0, column=3, sticky="w")
        self._slider(adj, 1, "震え(ビブラート)", "vc_vibrato", 0, 100, 0.0, "{:.0f}%")
        self._slider(adj, 2, "ロボット", "vc_robot", 0, 100, 0.0, "{:.0f}%")
        self._slider(adj, 3, "ロボットの高さ", "vc_robot_hz", 20, 200, 50.0, "{:.0f}Hz")
        self._slider(adj, 4, "リバーブ(響き)", "vc_reverb", 0, 100, 0.0, "{:.0f}%")
        ttk.Label(adj, text="フィルター").grid(row=5, column=0, sticky="w", pady=(4, 0))
        var = self._var("vc_filter", "なし", tk.StringVar)
        ttk.Combobox(adj, textvariable=var, values=list(VOICE_FILTERS), state="readonly",
                     width=14).grid(row=5, column=1, sticky="w", padx=4, pady=(4, 0))
        var.trace_add("write", lambda *_: self._apply())
        if signal is None:
            ttk.Label(adj, text="※scipy が無いのでフィルターは無効", foreground="gray").grid(
                row=6, column=0, columnspan=3, sticky="w")

    def _apply_preset(self, name):
        pitch, vib, robot, robot_hz, filt, reverb = VOICE_PRESETS[name]
        self.vc_preset.set(name)
        self.vars["vc_pitch"].set(pitch)
        self.vars["vc_vibrato"].set(vib)
        self.vars["vc_robot"].set(robot)
        self.vars["vc_robot_hz"].set(robot_hz)
        self.vars["vc_filter"].set(filt)
        self.vars["vc_reverb"].set(reverb)
        self.vars["vc_on"].set(name != "ノーマル")
        self._apply()

    def _build_effects_tab(self, nb):
        tab = ttk.Frame(nb, padding=8)
        nb.add(tab, text="エフェクト")

        dist = ttk.LabelFrame(tab, text="音割れ", padding=8)
        dist.grid(row=0, column=0, sticky="ew")
        self._check(dist, "音割れON", "dist_on", 0, 0)
        self._combo_inline(dist, "dist_mode", "ハードクリップ", ["ハードクリップ", "ソフト", "ビットクラッシュ"], 1)
        self._combo_inline(dist, "dist_target", "全体", ["全体", "マイクのみ", "デスクトップのみ"], 2)
        self._slider(dist, 1, "強さ", "dist_amount", 0, 100, 50.0, "{:.0f}")

        gate = ttk.LabelFrame(tab, text="ノイズゲート(マイク)", padding=8)
        gate.grid(row=1, column=0, sticky="ew", pady=(8, 0))
        self._check(gate, "ノイズゲートON", "gate_on", 0, 0)
        self._slider(gate, 1, "しきい値", "gate_db", -80, 0, -45.0, "{:.0f}dB")

        echo = ttk.LabelFrame(tab, text="エコー(マイク)", padding=8)
        echo.grid(row=2, column=0, sticky="ew", pady=(8, 0))
        self._check(echo, "エコーON", "echo_on", 0, 0)
        self._slider(echo, 1, "ディレイ", "echo_ms", 50, 1000, 250.0, "{:.0f}ms")
        self._slider(echo, 2, "繰り返し", "echo_fb", 0, 90, 0.4, "{:.0f}%", 0.01)
        self._slider(echo, 3, "エコー量", "echo_mix", 0, 100, 0.5, "{:.0f}%", 0.01)

    def _combo_inline(self, parent, key, default, values, col):
        var = self._var(key, default, tk.StringVar)
        ttk.Combobox(parent, textvariable=var, values=values, state="readonly",
                     width=14).grid(row=0, column=col, padx=4)
        var.trace_add("write", lambda *_: self._apply())

    def _build_soundboard_tab(self, nb):
        tab = ttk.Frame(nb, padding=8)
        nb.add(tab, text="サウンドボード")
        self.slots = []
        saved = self.cfg.get("soundboard", [])
        ttk.Label(tab, text="ファイル").grid(row=0, column=1, sticky="w")
        ttk.Label(tab, text="ホットキー").grid(row=0, column=4, sticky="w")
        for i in range(SOUNDBOARD_SLOTS):
            s = saved[i] if i < len(saved) else {}
            slot = {"path": s.get("path", ""), "cache": {}, "key": tk.StringVar(value=s.get("key", NONE))}
            name = tk.StringVar(value=os.path.basename(slot["path"]) or "(未設定)")
            slot["name"] = name
            ttk.Label(tab, text=f"{i + 1}").grid(row=i + 1, column=0, padx=(0, 4))
            ttk.Label(tab, textvariable=name, width=32, relief="sunken").grid(row=i + 1, column=1, pady=2)
            ttk.Button(tab, text="選択", width=5,
                       command=lambda sl=slot: self._choose_sound(sl)).grid(row=i + 1, column=2, padx=2)
            ttk.Button(tab, text="▶", width=3,
                       command=lambda sl=slot: self._play_slot(sl)).grid(row=i + 1, column=3, padx=2)
            ttk.Combobox(tab, textvariable=slot["key"], values=list(HOTKEYS), state="readonly",
                         width=10).grid(row=i + 1, column=4, padx=2)
            self.slots.append(slot)
        bottom = ttk.Frame(tab)
        bottom.grid(row=SOUNDBOARD_SLOTS + 1, column=0, columnspan=5, sticky="ew", pady=(8, 0))
        self._slider(bottom, 0, "サウンド音量", "sb_vol", 0, 200, 1.0, "{:.0f}%", 0.01)
        ttk.Button(bottom, text="■ 全部止める", command=self.mixer.stop_sounds).grid(row=0, column=3, padx=6)
        ttk.Checkbutton(bottom, text="自分にも聞こえるようにする(モニターで再生)",
                        variable=self.vars["monitor_sb"],
                        command=self._on_sb_monitor).grid(row=1, column=0, columnspan=4, sticky="w", pady=(4, 0))
        fmt = "WAV / MP3 / OGG / FLAC" if soundfile else "WAV"
        ttk.Label(tab, text=f"対応形式: {fmt}　※開始中のみ再生されます",
                  foreground="gray").grid(row=SOUNDBOARD_SLOTS + 2, column=0, columnspan=5, sticky="w")

    def _build_hotkey_tab(self, nb):
        tab = ttk.Frame(nb, padding=8)
        nb.add(tab, text="ホットキー")
        ttk.Label(tab, text="ほかのアプリを操作中でも効きます。", foreground="gray").grid(
            row=0, column=0, columnspan=2, sticky="w", pady=(0, 6))
        rows = [("音割れ(押している間だけ)", "hk_dist_hold"),
                ("音割れ ON/OFF 切り替え", "hk_dist_toggle"),
                ("マイク ミュート切り替え", "hk_mic_mute"),
                ("デスクトップ ミュート切り替え", "hk_desktop_mute"),
                ("エコー ON/OFF 切り替え", "hk_echo"),
                ("ボイチェン ON/OFF 切り替え", "hk_vc"),
                ("ボイチェン 次のプリセット", "hk_vc_next"),
                ("サウンド全部止める", "hk_stop_sounds"),
                ("開始 / 停止", "hk_start")]
        for r, (label, key) in enumerate(rows, start=1):
            ttk.Label(tab, text=label).grid(row=r, column=0, sticky="w", pady=2)
            ttk.Combobox(tab, textvariable=self._var(key, NONE, tk.StringVar), values=list(HOTKEYS),
                         state="readonly", width=12).grid(row=r, column=1, padx=8, pady=2)

    def _select_defaults(self):
        def pick(key, names, fallback):
            saved = self.cfg.get(key)
            var = self.vars[key]
            if saved in names:
                var.set(saved)
            elif fallback in names:
                var.set(fallback)
            elif names:
                var.set(names[0])

        dl = self.mixer.default_loopback()
        pick("desktop_dev", [NONE] + [d["name"] for d in self.loop_devs], dl["name"] if dl else NONE)

        try:
            dm = self.mixer.pa.get_device_info_by_index(
                self.mixer.pa.get_host_api_info_by_index(self.mixer.wasapi)["defaultInputDevice"])["name"]
        except Exception:
            dm = NONE
        pick("mic_dev", [NONE] + [d["name"] for d in self.mic_devs], dm)

        out_names = [d["name"] for d in self.out_devs]
        hint = next((n for h in OUTPUT_NAME_HINTS for n in out_names if h in n.lower()), "")
        pick("out_dev", out_names, hint)
        pick("monitor_dev", [NONE] + out_names, NONE)

    # ---- 動作 ----
    def _apply(self):
        p = self.mixer.params
        for key, (lab, fmt) in self.labels.items():
            v = self.vars[key].get()
            p[key] = v * self.vars[key + "_scale"]
            lab.config(text=fmt.format(v))
        for key in ("desktop_mute", "mic_mute", "monitor_sb", "monitor_mic", "monitor_desktop",
                    "dist_on", "gate_on", "echo_on", "dist_mode", "dist_target", "vc_on", "vc_filter"):
            if key in self.vars:
                p[key] = self.vars[key].get()

    def _find(self, devs, name):
        return next((d for d in devs if d["name"] == name), None)

    def toggle(self):
        if self.mixer.running:
            self.mixer.stop()
            self.start_btn.config(text="▶ 開始")
            self.status.config(text="停止中")
            return
        out = self._find(self.out_devs, self.vars["out_dev"].get())
        if not out:
            messagebox.showerror("エラー", "出力先デバイスを選んでください。")
            return
        desk = self._find(self.loop_devs, self.vars["desktop_dev"].get())
        mon = self._find(self.out_devs, self.vars["monitor_dev"].get())
        if desk and out["name"] in desk["name"]:
            if not messagebox.askyesno("確認", "デスクトップ音と出力先が同じデバイスです。\n"
                                       "音がループ(ハウリング)します。続けますか？"):
                return
        if desk and mon and mon["name"] in desk["name"] and self.vars["monitor_desktop"].get():
            if not messagebox.askyesno("確認", "モニター先がデスクトップ音と同じデバイスで、\n"
                                       "デスクトップ音もモニターに含まれています。ループします。続けますか？"):
                return
        try:
            self.mixer.start(desk, self._find(self.mic_devs, self.vars["mic_dev"].get()), out, mon)
        except Exception as e:
            messagebox.showerror("エラー", f"開始できませんでした:\n{e}")
            return
        self.start_btn.config(text="■ 停止")
        self.status.config(text=f"出力中 → {out['name']}")

    def _restart_if_running(self):
        if self.mixer.running:
            self.mixer.stop()
            self.toggle()

    def _tick(self):
        for k, pb in self.meters.items():
            pb["value"] = min(1.0, self.mixer.levels[k])
        self.root.after(50, self._tick)

    # ---- サウンドボード ----
    def _on_sb_monitor(self):
        """モニター先が未設定なら、Windowsの既定スピーカーを自動で選ぶ。"""
        self._apply()
        if not self.vars["monitor_sb"].get() or self.vars["monitor_dev"].get() != NONE:
            return
        try:
            idx = self.mixer.pa.get_host_api_info_by_index(self.mixer.wasapi)["defaultOutputDevice"]
            name = self.mixer.pa.get_device_info_by_index(idx)["name"]
        except Exception:
            return
        if name != self.vars["out_dev"].get():
            self.vars["monitor_dev"].set(name)

    def _choose_sound(self, slot):
        types = [("音声ファイル", "*.wav *.mp3 *.ogg *.flac"), ("すべて", "*.*")] if soundfile \
            else [("WAV", "*.wav")]
        path = filedialog.askopenfilename(title="サウンドを選択", filetypes=types)
        if path:
            slot["path"] = path
            slot["cache"] = {}
            slot["name"].set(os.path.basename(path))

    def _play_slot(self, slot):
        if not slot["path"] or not self.mixer.running:
            return
        rate = self.mixer.rate
        if rate not in slot["cache"]:
            try:
                data, src = load_audio(slot["path"])
            except Exception as e:
                messagebox.showerror("エラー", f"読み込めませんでした:\n{e}")
                return
            slot["cache"] = {rate: resample_whole(data, src, rate)}
        self.mixer.play(slot["cache"][rate])

    # ---- ホットキー ----
    def _pressed(self, key):
        """押された瞬間だけ True。"""
        down = key_down(HOTKEYS.get(key, 0))
        was = self.key_state.get(key, False)
        self.key_state[key] = down
        return down and not was

    def _flip(self, key):
        self.vars[key].set(not self.vars[key].get())
        self._apply()

    def _poll_keys(self):
        hk = {k: self.vars[k].get() for k in self.vars if k.startswith("hk_")}
        self.mixer.params["dist_hold"] = bool(key_down(HOTKEYS.get(hk["hk_dist_hold"], 0)))

        # 同じキーが複数に割り当てられていても1回の押下で全部動くよう、キーごとに判定する
        edges = {}
        names = set(hk.values()) | {s["key"].get() for s in self.slots}
        for name in names - {NONE}:
            edges[name] = self._pressed(name)

        if edges.get(hk["hk_dist_toggle"]):
            self._flip("dist_on")
        if edges.get(hk["hk_mic_mute"]):
            self._flip("mic_mute")
        if edges.get(hk["hk_desktop_mute"]):
            self._flip("desktop_mute")
        if edges.get(hk["hk_echo"]):
            self._flip("echo_on")
        if edges.get(hk["hk_vc"]):
            self._flip("vc_on")
        if edges.get(hk["hk_vc_next"]):
            names = list(VOICE_PRESETS)
            cur = self.vc_preset.get()
            self._apply_preset(names[(names.index(cur) + 1) % len(names)] if cur in names else names[0])
        if edges.get(hk["hk_stop_sounds"]):
            self.mixer.stop_sounds()
        if edges.get(hk["hk_start"]):
            self.toggle()
        for slot in self.slots:
            if edges.get(slot["key"].get()):
                self._play_slot(slot)
        self.root.after(15, self._poll_keys)

    # ---- 設定 ----
    def _load_config(self):
        try:
            with open(CONFIG_PATH, encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            return {}

    def _save_config(self):
        cfg = dict(self.mixer.params)
        cfg.pop("dist_hold", None)
        for key, var in self.vars.items():
            if isinstance(var, tk.Variable) and key not in self.labels:
                cfg[key] = var.get()
        cfg["vc_preset"] = self.vc_preset.get()
        cfg["soundboard"] =[{"path": s["path"], "key": s["key"].get()} for s in self.slots]
        try:
            with open(CONFIG_PATH, "w", encoding="utf-8") as f:
                json.dump(cfg, f, ensure_ascii=False, indent=2)
        except Exception:
            pass

    def on_close(self):
        self._save_config()
        self.mixer.close()
        self.root.destroy()


if __name__ == "__main__":
    root = tk.Tk()
    App(root)
    root.mainloop()
