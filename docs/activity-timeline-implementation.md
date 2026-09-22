# Activity Timeline 実装報告

実装日: 2026-09-23 / ブランチ: `codex/activity-timeline`

## Phase 1

定期取得、複数画面の識別とbounds、Win32 foreground metadata、類似画面判定、
process/title除外、pause/resume、画像retentionを実装した。
既定は無効。Springの共通schedulerは処理を専用の1-worker executorへ渡すだけで、
Chatをブロックしない。処理失敗を隔離し、pause後のin-flight結果を保存しない。

主要クラス: `ActivityCapture`, `DesktopActivityObserver`, `WindowsDesktopActivityObserver`,
`CapturePolicy`, `ImageChange`, `ScreenshotStore`, `FileScreenshotStore`, `ActivityProperties`,
`ActivityConfiguration`, `ForegroundWindow`。

## Phase 2

`ActivityExtractor` / `VisionActivityExtractor` により既存LLMモデル基盤を再利用。
専用JSON Schemaと `ActivityOutputParser` で出力を検証する。
画像を別サーバーに暗黙フォールバックしないように `LlmModelProvider` を拡張した。
推論失敗のAgent Eventにはproviderの生のエラーメッセージを含めない。

`ActivityRecord` は以下を保持する。

- id、capturedAt、durationEstimate、continuityId
- OS evidence: モニターごとのobservations、foreground process/PID/title/window ID
- 推論: summaryと複数activities（monitor/type/application/service/contentTitle/projectCandidate）
- confidence、画像参照、changeAmount、duplicate

Visionの内容はすべて推論側に配置し、OSの観測事実を上書きさせない。
重複画面の再解析は省略するが、時間情報のための軽量Recordは残す。

## Phase 3

`SessionMergePolicy` で連続した類似Recordを集約し、`SqliteActivityStore` が
Recordと日次Session projectionを同じトランザクションで永続化する。
画像retentionからRecord/Sessionを分離した。

`ActivitySession` は id、startedAt、endedAt、observedSeconds、全recordIds、
代表inference、primaryApplication、confidenceを保持する。
pauseなどの境界、大きなgap、異なるActivity、日付境界を考慮し、重複推定時間を切り詰める。

`ActivityTimeline` が today/yesterday/指定日/指定時間帯と日次summaryを提供。
`ActivityCommand` を `RootCommand` へ追加し、`ActivityTools` をChatのTool群に登録した。
自然言語の判定は既存Agentに任せる。日次summaryはSessionの決定的な文章化で生成する。

## 利用と設定

Slash commands: `/activity today`, `/activity yesterday`, `/activity summary`,
`/activity YYYY-MM-DD`, `/activity pause`, `/activity resume`。

設定: `rei.activity.enabled`, `extraction-enabled`, `capture-interval-seconds`,
`screenshot-retention-days`, `change-threshold`, `session-gap-seconds`, `zone`,
`excluded-processes`, `excluded-window-title-patterns`。
モデル設定は `rei.llm.features.activity`。
Windowsの対話セッションと `-Djava.awt.headless=false` が必要。

詳細は [Activity Timeline仕様](activity-timeline.md) を参照。

## テスト結果

- Java全件: **2003件成功、failure/error/skipは0**。
- 今回追加: Activityパッケージ50件 + LLMの画像フォールバック禁止1件 = **51件**。
- クライアント単体: **41件成功**。
- Rust: **64件成功**（doc-testsは0件）。
- ブラウザE2E: **12件すべて成功を確認**。全件再実行は11件成功・既存desktop avatarテスト1件が60秒でタイムアウトし、その1件の単独再実行は43.7秒で成功。
- Win32 probe用PowerShell: AST構文解析成功。実画面・実Vision APIは自動テストで呼んでいない。
- `git diff --check`: 成功。

TDDはドメインテストのRed（未実装によるコンパイル失敗）から開始し、
Green後にポリシーとPort/Adapterを分離した。summaryの言い換え、pause境界、
推定区間の重複、画像のfallback禁止は失敗を確認した回帰テストから修正した。

Javaの初回全件実行はsandboxの保存先制限とsqlite-vecダウンロード制限で失敗した。
テスト専用 `REI_DATA_DIR` を `target/activity-test-data` に設定し、
必要なネットワークアクセスを許可して全件を再実行した。
MavenはJDK25とローカル `.m2/repository` を使用。
ブラウザE2Eの初回は画像naturalWidthの待機でdesktop/mobileの2件が失敗した。
クライアントのコード・テスト・画像は本変更では編集していない。
E2Eは一発で全件greenではなく、実行負荷による不安定性が残る。

## 将来用データ / 意図的な未実装 / 残課題

元の時系列、推定時間、foreground、モニター情報、変化量、並行Activity、
project/content/service候補、confidence、継続境界を残した。
将来のswitch回数・session length・primary activity/project等の集計に利用できる。
focus/idleは実測していないため、後続のAnalyticsで推定と実測を混同しないこと。

未実装: Behavior Evaluation、お小言、2〜6の採点、週次/月次分析、Adaptive Coaching、
Project/Task/Working Set等の深い統合、専用クライアントTimeline画面。

残課題: 実機の複数モニター/HiDPI/実Visionモデルでの確認、foreground以外の機密表示の
マスキング、高頻度化時の日次projection差分更新、スキーマ移行、実操作/idle evidence。
現在の除外はforeground主体であり、全画面の機密情報を自動検出するものではない。
