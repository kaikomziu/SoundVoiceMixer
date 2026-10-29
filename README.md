# SoundVoice Mixer
n公式サイト: https://kaikomziu.github.io/SoundVoiceMixer/

Windows 用の**音声ミキサー**です。デスクトップの音（再生中の音）と**マイク**をミックスして、仮想オーディオケーブルへ出力します。
ボイチェン・音割れ・ノイズゲート・エコー・サウンドボード・ホットキーなどが使えます。スマホ（Android）版もあります。

## Windows 版

### 仕組み
デスクトップ音（WASAPI ループバック）と指定したマイクをミックスして、仮想オーディオケーブル（例: [VB-CABLE](https://vb-audio.com/Cable/) の `CABLE Input`）へ出力します。
録音側のデバイス名を `soundvoice Output` にしておくと、他のアプリからは「`soundvoice Output` という仮想マイク」として見えます。

### 機能
- **ミキサー**: デスクトップ音・マイク・マスターの音量調整（0〜300%）、ミュート、モニター（自分で聞く）
- **ボイチェン**: マイクの声を加工。プリセット 14 種（女声風・男声風・子ども・ヘリウム・魔王・ロボット・宇宙人・おばけ・電話・ラジオ・メガホン・洞窟 ほか）と、ピッチ・震え・ロボット・リバーブ・フィルターの細かい調整
- **エフェクト**: 音割れ（ハードクリップ / ソフト / ビットクラッシュ）、ノイズゲート、エコー（マイク）
- **サウンドボード**: 8 枠に音声ファイルを割り当てて再生
- **ホットキー**: サウンドボードなどをグローバルホットキーで操作
- 起動時に自動で開始する設定

### ダウンロード
[Releases](../../releases/latest) の `SoundVoiceMixer.exe` を実行します（Python は不要）。設定は exe の隣の `settings.json` に保存されます。

### 自分で動かす
```bat
pip install -r requirements.txt
pythonw soundvoice_mixer.py
```
（`SoundVoice Mixer.bat` でも起動できます）

exe にするには、次のようにします。

```bat
pip install pyinstaller
python -m PyInstaller --noconfirm --onefile --windowed --name SoundVoiceMixer --icon icon.ico --exclude-module matplotlib --exclude-module pandas --exclude-module IPython soundvoice_mixer.py
```

## Android 版（SoundVoice）

[`android/`](android) のアプリです（Java）。マイク、スマホ内の音（ほかのアプリの音）、サウンドボードをミックスして、エフェクトをかけ、イヤホン / スピーカーで聞けます。録音もできます（保存先: ミュージック/SoundVoice）。ボイチェン・音割れ・ノイズゲート・エコーも使えます。

- イヤホン推奨です（スピーカーだとマイクが拾ってハウリングします）。
- Android の仕様で、Discord などほかのアプリのマイクに流すことはできません。**聞く・録音する用途向け**です。
- 動作条件: Android 10（API 29）以上。
- apk は Releases にあります（デバッグ署名のため、提供元不明アプリのインストールを許可する必要があります）。自分でビルドするときは、`android/` で `gradlew assembleDebug` を実行します。
