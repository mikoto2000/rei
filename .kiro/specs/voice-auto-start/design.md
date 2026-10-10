# 音声入力の起動時自動開始

## 要件

- `rei.voice.auto-start` は既定false。未設定の起動に録音・モデル取得を追加しない。
- 明示有効化した対話Shellだけで、既存のモデル検証・明示デバイス・VOICE送信経路を利用する。
- 不足や初期化失敗でShellを終了しない。自動再試行やモデル取得はしない。
- 起動準備を非同期化し、OFF・終了・手動操作を優先する。元の送信先を保持し、古い開始処理が後続の録音を停止しない。

## 設計

`ReiApplication.runShell` は出力、イベント購読、Shell client scope、console/prompt資源を準備した後に
`VoiceCommand.autoStart` を呼ぶ。Spring起動イベントやBean生成にはフックしない。
JLineの非dumb system terminalとproviderのstdin-TTY判定で対話性を確認し、確認不能・判定失敗時は開始しない。

`VoiceCommand` は初期client/target/deviceを捕捉し、client scopeを開いた専用virtual threadでローカルモデル検証を行う。
受付時は `/voice on`・`/voice test` と共通の開始経路へ進む。手動操作・終了は世代を無効化して準備を割り込む。
遅れて完了した検証は開始直前の世代・送信先チェックで拒否する。自動失敗の停止処理も世代を照合する。

`VoiceInputCoordinator.start` の返す `Startup` はRun固有の待機ハンドル。
割込み・タイムアウトで止められるのはそのRunだけとし、OFF→ON後の後続Runには干渉しない。
既存の `awaitStartup` も同じRun固有の待機実装を利用する。明示OFFは既知の開始失敗を確認済みとして扱い、
実際の資源解放失敗は引き続きイベント・FAILEDで報告する。

## 検証

先行テストは未実装のプロパティ・開始APIによるコンパイル失敗を確認してから実装した。
既定値・バインド、無効/非対話起動、一度だけの開始、モデル不足・マイク未選択・JNI失敗、
検証中/初期化中のOFF、終了、送信先変更、手動再開、古い待機と後続Runの分離をfake/mocksで検証する。
実モデル取得・実マイク・Windowsの実機確認はこの変更の自動テストに含めない。
