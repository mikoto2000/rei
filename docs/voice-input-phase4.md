# 音声入力 Phase 4 — 運用品質と安全性

> この文書の base INT8・取得ID・時間上限・実機結果は当時の記録です。現在のモデルと取得・移行手順は [Whisper turbo FP32 移行](voice-input-turbo-fp32.md) を参照してください。

## 実装範囲

Phase 3 の固定モデル管理と Phase 2 の音声処理に、Windows endpoint 監視、送信先再確認、認識確認・訂正、およびネイティブ推論のプロセス隔離を追加する。Java 25、DRY (VT-4)、CPU/base multilingual INT8 を維持する。初期状態は OFF、確認モードも初期 OFF。

## デバイスと停止・復旧

`WindowsAudioEndpoints` は Java 25 FFM から Windows x64 の MMDevice API を読み取り、capture endpoint の ID・完全な FriendlyName・状態を取得する。OS の Ole32.dll を絶対パスでロードし、COM オブジェクト、PROPVARIANT、文字列、COM 初期化、Arena を query ごとに解放する。起動時には query やマイク録音を行わない。実行には `--enable-native-access=ALL-UNNAMED` が必要。

`WindowsMicrophoneMonitor` は、選択した Java Sound デバイスの完全な名前と一致する ACTIVE endpoint が一つある場合だけ結び付ける。同名の複数 endpoint、部分一致、既定マイクへのフォールバックを許可しない。同じ Java Sound ID に別の endpoint ID が現れた場合は、明示的な device set を必要とする。ID が環境を越えて不変とは仮定せず、この対応表もプロセス内で管理する。

`GuardedMicrophoneCapture` はマイクを開く前と直後に状態を確認する。録音中の supervisor は 250 ms ごとに endpoint の状態を確認するため、切断後にドライバが無音フレームを返し続けても、それだけで正常と判断しない。切断・無効化・ID 置換・名前変更を検出したら停止する。

フレームの到着に monotonic clock と wall clock の両方を使う。5 秒を超える録音の中断または wall clock の逆行を検出した場合、復帰後のバッファと遅い認識結果を破棄する。認識が supervisor より先に返っても、送信直前に中断と送信先を再確認する。短い中断をすべて OS の suspend と識別する実装ではない。

切断・録音中断・録音/VAD 失敗の後は選択を解除する。再接続後は以下を実行する。

```text
/voice off
/voice status
/voice devices
/voice device set <表示された ID>
/voice on
```

STOPPING 中は再開しない。選択 Project/Session が変わった場合は OFF へ戻り、古い発話を送らない。新しい送信先で on を実行する。別の Web/CLI client の選択変更では捕捉した Shell client の送信先を変更しない。

## 認識の確認・訂正

```text
/voice config --confirmation true
/voice on
/voice pending
/voice confirm <認識 ID>
/voice confirm <認識 ID> --text "訂正した文章"
/voice pending cancel <認識 ID>
/voice off
/voice config --confirmation false
```

設定変更は OFF/FAILED 時のみ。VAD 設定の検証が失敗したら確認設定も変更しない。確認待ちは最大 3 件、2 分、メモリ内のみ。自動送信から確認へ切り替えても過去の入力を再送せず、確認を無効にしても保留分を勝手に送らない。off は確認待ちを破棄する。

確認・訂正は同じ UUID、VOICE provenance、Project/Session、発話時刻を保ち、共通 ConversationInputGateway を通る。送信失敗時は訂正文を保持して再試行できる。成功分は除去する。無効・期限切れ・取消済み・送信先変更後の ID は送信できない。スラッシュコマンド形式の訂正も拒否する。

確認待ちのイベントは ID だけを表示する。認識文の表示は明示した pending 操作で行う。実行待ち VOICE と認識確認待ちは異なる段階であり、いずれも取消できる。ASR キューは 2 件、共通 VOICE 実行待ちは既存の 3 件の上限を維持し、満杯を通知する。

## Tool 承認と provenance

認識文の確認は Tool 実行の承認ではない。`AgentRunContext.voiceInput` を共通 Gateway で設定し、RunRegistry、checkpoint、resume、アプリの SubAgentRunner へ引き継ぐ。旧 JSON と既存のテキスト入力は false のまま互換性を維持する。RequestSource と既存の実行 Mode は変更しない。

VOICE 由来の Run では、既存 Policy が AUTO と判断しても、既知の intrinsically read-only Tool 以外は既存の明示承認経路を要求する。DENY や restricted Mode は維持する。ポリシー無効化や管理者による unknown Tool の READ 分類でもこの確認を迂回できない。承認は既存の Tool・入力引数単位の仕組みを使い、音声の slash command や認識確認を承認として扱わない。

## ネイティブ推論と表示保護

`IsolatedVoiceBackendFactory` は VAD と ASR を別々の所有子 JVM で起動する。`NativeVoiceWorker` は Spring、scheduler、Agent、マイク、ネットワークを起動しない。VAD worker は VAD モデルのみ、ASR worker は Whisper のみを読み込む。CPU 1 thread と固定サイズ/SHA 検証を維持する。

`VoiceWorkerProcess` の binary IPC は version、opcode、sample 数、有限 PCM、確率、UTF-8 response サイズを検証する。音声は最大 28 秒、応答は最大 64 KiB。起動は各 worker 15 秒、VAD 要求は 5 秒、ASR 要求は 60 秒の上限。認識中の停止・timeout・異常終了では、この factory が起動した子プロセスだけを終了する。親 JVM には推論 JNI のポインタを持たず、他の Rei/Java プロセスを終了しない。

子 JVM の native stdout は IPC として検証し、stderr は排出して端末へ流さない。stderr の raw 内容は保存せず byte 数だけを保持する。native crash dump の生成も抑制する。通常停止は stdin EOF による資源解放を待ち、停止しない所有 worker を終了する。録音ドライバの close も専用 daemon で実行し、停止要求の Shell caller を塞がない。実際の録音/解放が完了するまで STOPPING を維持し、次の on と親バックエンド解放を抑止する。直接 JNI factory は独立したローカル統合試験用途として残すが、本番 coordinator には隔離 factory を接続する。

状態・失敗・キュー満杯・確認 ID は既存 `JLineShellEventOutput.printAbove` の経路へ出力する。デフォルトイベントに認識文や音声フレームを含めない。明示した voice test と手動受け入れ試験のみ診断結果を表示する。通常の認識文は、送信先 Session の既存の会話・turn 保存と Agent 処理の対象となる。raw 音声はファイルへ保存しない。

`/voice models verify` は配置済み一式のサイズ・SHA を検証する。取得、モデルロード、マイク起動を行わない。破損・未配置は失敗する。

## 検証状況

- Red → Green: Windows endpoint の完全一致・同名曖昧性・切断/無効化/置換/名前変更、マイク open 前後の競合。
- Red → Green: 確認・訂正・期限・上限・取消・誤った送信先、送信失敗時の訂正文保持。
- Red → Green: VOICE の Tool 承認と DENY 維持、Run/Checkpoint の保存・復帰と旧 JSON の互換性。
- Red → Green: 選択変更・wall clock 中断・無音を返す故障 source・mock の開始/停止 12 回。
- Red → Green: 専用 process の native stderr 排出、crash、timeout、不正確率/handshake、PCM 上限。
- Windows 実機で DRY (VT-4) の ACTIVE endpoint を完全一致で一意に取得した。録音は行わない inventory 検査。
- 実ローカルモデルを使う隔離 VAD/ASR と直接 JNI の各 3 回の日本語合成音声試験、所有 worker 終了確認は成功。
- 関連入力・承認・永続化・音声テスト 89 件成功。
- 実 JLine（仮想 terminal）で日本語入力途中に状態/queue/Agent stream/切断通知を表示し、後続文字と Enter で入力全文が保たれる試験は成功。モデル verify と合わせ 5 件成功。
- 実ローカルモデルの隔離 VAD/ASR を 20 回開閉し、毎回の所有 worker 終了を確認した（65.67 秒、成功）。
- 配布 Boot JAR 内の factory から native child JVM を起動して合成日本語音声を 3 回認識し、毎回 worker=0 を確認した。Spring context とマイクは起動しない。
- ドライバの close を停止させても voice off は戻り、再開と早すぎる backend 解放を防ぐ Red → Green 試験、および実 Tool callback の引数一致・一回限り承認を VOICE と既存テキストの両方で検証した。関連 22 件成功。
- 更新後の Windows 実マイク試験は、新しい準備 OK と明示した受付開始から実施した。DRY (VT-4) -> guarded capture -> isolated raw VAD/base INT8 -> selected-client common gateway -> existing Agent -> COMPLETED turn 保存 -> OFF -> worker 終了を確認した。Enter による会話投入は行わない。
- 実マイクの数値: 624 frames、319488 samples（19.968 秒）、nonzero 195923、peak 0.249786、RMS 0.020616、VAD peak 0.999465、threshold 以上 130 frames、ASR 1 回。raw 音声ファイルは保存しない。
- 実認識文は「こんにちは、音声入力のテストエスト。くしてください。」。指定文の誤変換・欠落があり、正確な全文認識や短い挨拶の達成とは扱わない。Agent は既存 Normal スタイルで応答した。精度は Phase 5、応答スタイルは Phase 7 の改善対象とする。
- 通常 package プロファイルの 2209 件は成功した。CI と同じ deadline 付き full 回帰は 4234 件、失敗 0、エラー 0、既存 PlantUML のスキップ 1 件で成功した（12:11）。その後に最新 JAR を再生成した。CI と main 統合の最終結果は PR に記録する。
- 実機を物理的に sleep させる試験と USB 抜去は未実施。時間・endpoint 変化を注入した試験と実機 inventory の結果を、物理操作の成功と扱わない。

認識精度、短い発話や途中確定のパラメータ比較は Phase 5。GPU、wake word、割り込み、発話者識別、TTS は Phase 6。会話の応答スタイルは Phase 7 とし、直前 Phase の main 統合まで実装に進まない。
