# 音声認識の文脈依存 LLM 補正

Whisper 確定文を、短い直近会話と用語辞書を参照する専用 LLM で補正します。漢字・同音異義語・技術用語・脱字が対象です。辞書完全一致は必要ありません。検証に成功した候補を自動採用し、従来の VoiceDeliveryService → ShellConversationService → ConversationInputGateway へ VOICE のまま送ります。通常のテキスト入力は補正しません。

## 設定と接続

既定 OFF。Rei データディレクトリの application.yaml に設定します。

```yaml
rei:
  voice:
    correction:
      enabled: false
      timeout-ms: 5000
      max-input-chars: 500
      max-context-chars: 1000
      max-output-tokens: 512
      max-concurrent-requests: 1
      max-queued-requests: 3
      max-dictionary-entries: 64
      max-total-tokens: 4096
  llm:
    features:
      voice-correction:
        base-url: http://localhost:11434
        model: qwen3.5:9b
        api-key: dummy-key
```

`voice-correction` は既存 LlmModelProvider / LlmChatClientProvider の独立用途です。接続先省略時は `rei.llm.features.chat` の接続設定、さらに省略時は Spring AI の通常接続を継承します。補正モデルだけの指定もできます。明示した接続先で失敗しても別サーバーへ再送しません。Tool callbacks / raw tools を禁止し、履歴・記憶・Agent Advisor・Planning Loop を付けません。SDK retry は0。補正1発話につき1回の LLM 呼出しと OutputLimitRunBudget による独立予算を使います。Agent Run / Goal は正式受付後に作られるため、その予算や承認を補正が消費・付与しません。`max-total-tokens: 0` はトークン会計の上限を無効にします。正の上限で usage 不明／超過なら原文に戻します。

**有効化すると、ASR 原文・短い会話文脈・辞書・Project ID と名前が設定済みリモート LLM に送られる場合があります。** 有効化後の発話ごとに追加の接続承認は求めません。Project の絶対パス、全履歴、Tool 結果、長期記憶、Web 検索は渡しません。既知の資格情報は文脈・辞書でマスキングし、ASR 原文に検出した場合は LLM を使いません。未知の自由文中の秘密情報を完全検出する保証はありません。機密会話では OFF を使ってください。

マイク OFF 時に `/voice correction --enabled=true` / `--enabled=false` で、そのプロセスの設定を変更できます。永続化は application.yaml を変更してください。`/voice correction` は状態と診断IDだけを表示します。`/voice correction --show ID` は本人の明示操作で原文・候補 JSON・検証理由・採用文・最終送信文を表示します。診断は最大8件、2分、メモリ内のみです。未送信では submitted は null。認識確認で人が訂正した場合も、最終受理文を submitted に反映します。

ASR原文は呼びかけ・フィルター適用前のWhisper戻り値です。別に correction input（既存フィルターと呼びかけ除去後）を保持し、この本文だけをLLMに渡します。edits の Unicode code point 座標は correction input に対する座標で、ASR原文全体の座標とは区別します。原文は補正で上書きしません。

## 辞書と文脈

共通: `<REI_DATA_DIR>/voice/terms.json`。
Project: `<REI_DATA_DIR>/projects/<project UUID>/voice/terms.json`。

```json
[
  {"canonical":"Rei","reading":"れい","aliases":["レイ","Ray"],"description":"このプロジェクトの名前"},
  {"canonical":"Spring AI","reading":"すぷりんぐあい","aliases":["スプリング愛"]}
]
```

同じ正規表記・読み・別名の衝突では Project の項目を優先します。自動文字列置換はしません。1ファイル64KiB／128項目、採用64項目（設定可）、1項目の正規表記・読み80文字、別名8個×80文字、説明160文字が上限。不正 JSON・読込失敗は空辞書扱いで継続します。辞書も LLM への命令ではなく JSON 参照データです。

発話処理開始時に、固定した Project/Session の会話ログから最大4件の user/assistant 本文を取得し、最新から合計1000 Unicode code pointsまでに制限します。snapshot は補正キューに入る前に固定します。別 Session、Toolメッセージは含みません。

## 採用と安全境界

専用 system prompt は VoiceCorrectionLlm.SYSTEM。入力・参照データに含まれる命令に従わず、回答・要約・創作・推測による操作の追加を禁じます。正常・無変更・危険変更拒否の例を含みます。

JSON は `status`（corrected/unchanged/uncertain）、`text`、`edits`、`diagnostic` のみ。edits は start/end/before/after のみで、0起点 Unicode **code points**、end 排他、原文の位置順です。Java 側で型・必須／未知フィールド・重複キー・末尾 JSON・サイズ・編集順・before一致・全編集の再構築一致を検証します。unchanged/uncertain は原文と完全一致・edits空が必須です。confidence は採否に使いません。

数値・数量・日時・否定・URL・パス・ファイル名・コマンド断片の抽出結果が変わる候補、制御／承認応答や slash command を新しく作る候補、大量変更、制御文字を拒否します。短いカタカナ技術語から英字への補正は許容します。操作語を含む原文／候補の変更は保守的に拒否します。重要操作を含む原文は、補正成功以外にも timeout／通信失敗／不正出力時を含め、認識確認 inbox に置きます。`/voice confirm ID [--text 正しい文]` または `/voice pending cancel ID` を使います。認識確認は Tool 承認とは別です。

原文の呼びかけを先に VoiceWakeGate で判定し、「実行を停止」と承認／確認応答は補正を通しません。`/voice test` は ASR 原文の診断のみで、補正 LLM・Agent を呼びません。TTS と自己音声抑止、`/mode auto|normal|conversation`、通常の pending/confirm/cancel は従来経路です。

**機械検証だけで意味保存を完全保証できません。** 操作語の網羅性、ひらがなによる数値、未知のパスや暗黙の対象の判別には限界があります。このため最終 Tool・引数に紐付く既存 VOICE 承認を必ず維持します。自動採用や会話モード、音声で「はい」と言うことは副作用 Tool の承認になりません。

## 非同期処理、履歴、計測

独立した bounded executor（既定1実行＋3待機）と deadline timer を使い、文脈取得・キュー待ちを含めて5秒で原文へ戻します。キュー満杯も原文へ戻します。通信への interrupt と、結果の失効は別管理です。中断を無視する通信は worker を占有しますが、完了済み発話を書き換えません。完了順が逆でも通常会話の受付はASR順を維持します（最大64件の未受付発話、超過は破棄）。原文の実行停止だけは先行補正を待たず処理します。

voice Run、ASR segment UUID、audio gate epoch、選択世代、Project/Session を保持し、送信直前に再検証します。Session/Project を変えて元へ戻しても失効します。音声OFF・アプリ終了・取消後の候補、二重完了は送信しません。認識確認後の Gateway が最終入力IDの重複を拒否し、Userフレーム・Session History は正式受理文だけを使います。通常入力の生本文を診断イベント／ログに出しません。LLM Request Capture は既存の keyboard CHAT Root Run のみで、補正リクエストは記録対象に追加しません。

補正実行／補正キュー待ちの発話も `/voice pending` に `correction:` として表示し、`/voice pending cancel ID` で個別に取り消せます。取消は原文フォールバックを送信せず、その発話を破棄します。認識確認待ち／Agent実行待ちの既存取消も維持します。

VAD計算時間（前回区間完了／resetからの累計）、ASR時間、補正context_ms、queue_ms、llm_ms、validation_ms、additional_ms は内容なしの音声イベントとして取得できます。additional_ms は補正処理開始から判断完了までです。ASR順の受付待ち／認識確認の待ち時間は含みません。正式な送信通知時の agent_admission_additional_ms は補正開始から受付までの総時間で、人の認識確認待ちを含む場合があります。Agent 応答時間／first token は既存 Agent イベントで別計測します。補正は同期 call のため最初のトークン時刻は取得不可です。これらから根拠なしの改善率を算出しません。

## 評価と開発検証

`scripts/evaluate-voice-correction.py dataset.jsonl` はオフライン評価用です。原文のみ・辞書のみ・辞書＋LLM の実測 hypothesis を各サンプルの results.raw/dictionary/llm に用意します。本人同意済みの100〜200件を推奨し、各行に consent=true、reference、terms（正解の技術用語リスト）、results を指定します。各結果は text、additional_ms、overcorrection、intent_changed、critical_argument_broken、failed、timeout を持ちます。後ろ5項目は人による意味評価／実測結果の真偽値です。辞書のみ baseline は人が作成した文脈上安全な結果を使い、無条件同音置換を製品へ入れません。

CER（Unicode code point編集距離、句読点込み）、用語正解率、過補正／意図変更／重要引数破壊／失敗／timeout率、追加遅延p50/p95を出します。用語がないと正解率は null。本人の音声を自動収集・保存しません。実サンプルと音声実機評価は未評価です。合成テストの成功を本人の精度改善と扱いません。

通常単体テストは Fake LLM／仮想deadline／仮想マイクで検証します。任意の実LLM統合試験は `VoiceCorrectionLiveTest`（liveタグ）です。明示的に `REI_VOICE_CORRECTION_LIVE=true`、`REI_VOICE_CORRECTION_LIVE_URL`（OpenAI互換base URL、/v1まで）、`REI_VOICE_CORRECTION_LIVE_MODEL`、必要なら `REI_VOICE_CORRECTION_LIVE_KEY` を設定し、`mvnw.cmd -Plive-e2e -Dtest=VoiceCorrectionLiveTest test` を実行してください。この試験は合成の誤認識／文脈だけを送信します。

## 調査した既存資料との関係

起点は origin/main `4349ae3e758a324c9783373ca16bed469d14f4f5`。voice-input-implementation-report、phase4/5/6/7、turbo-fp32、llm-request-capture、session-history、program-structure と実コードを確認しました。古い構造説明の Session JSON/Turn の保存表に対し、この起点では storage migration 基盤が追加されています。補正は保存実装を固定せず、現行 ConversationLogStore と既存 Gateway を使います。phase7 のテスト件数は当時の記録で、今回の結果ではありません。補正診断は ASR生音声の保存機能を追加しません。

## ローカル検証記録（2026-10-10）

- JSON/Unicode編集契約、危険なパス先頭・漢数字・操作対象変更、呼びかけ付きASR原文保持で先行失敗を確認し、実装後に成功を確認しました。Fake LLM、仮想deadline、仮想マイクを使っています。
- Java全回帰 `mvnw.cmd -q -Pfull test`: 4,510件、失敗0、エラー0、既存条件付きskip1。その後の口語操作保護・個別取消・原文分離・追加統合テストを含む最終関連回帰: 213件、失敗0、エラー0、skip0。最終head全体の結果はPRのJava regression CIを参照してください。
- React/Vitest: 99件成功。Rust/Cargo: 120件成功。Playwright Chrome UI: 38件成功。TypeScript型チェック成功。
- オフライン評価器の合成例でCER計算を検算しました。実音声や実LLMの改善率・遅延評価ではありません。
- 本人同意済み評価データ未提供のため、実マイク/Whisper/補正LLMの組合せによる精度・過補正・意図変更率と追加遅延は未評価です。明示opt-inのlive試験は実行していません。
