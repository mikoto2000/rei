# Activity Timeline 実装報告

実装日: 2026-09-23 / ブランチ: `codex/activity-timeline`

Phase 1〜3 の初回実装は `082f744173c59b52461a2f5771cb9de17dbe1f3a`。
同じブランチでMemory-First I/O最適化を追加した。機能範囲とRecord/Session形式は維持し、
画像Evidenceを既定で保存しないよう変更した。

## Memory-First追加実装

### 調査した変更前のScreenshot lifecycle

```text
RobotScreenCapture → CapturedScreen / DisplayCapture / BufferedImage (RAM)
→ ImageChangeの類似判定 (保存前)
→ changedだけVisionActivityExtractor
→ ImageIO.write(OutputStream) → ByteArrayOutputStream → byte[]
→ ByteArrayResource / Media → OpenAI互換クライアントのBase64 data URL
→ Vision成功 → FileScreenshotStoreにPNG保存 (retention > 0)
→ 構造化ActivityRecord / ActivitySessionをSQLite保存
```

重複判定は既に保存前であり、明示的なVision用temporary PNGもなかった。
ただし、ImageIOのOutputStream版writeは内部でディスクキャッシュを選択し得た。
重複後の変更画像は成功時に毎回Evidenceとして保存していた。
Retention対象は成功時に保存したモニター別PNG（DB保存失敗時の孤立PNGも含む）。

### 変更後のScreenshot lifecycle

```text
Capture / privacy / duplicate detection (RAM)
├─ duplicate → 新規画像保存0回・Vision0回・軽量Recordのみ継続
└─ changed → MemoryCacheImageOutputStream / byte[] → Vision
   ├─ success → optional成功Evidence → 構造化Record → 画像を解放
   └─ failure → optional失敗Evidence → 画像を解放（不正Recordは保存しない）
```

- `ActivityCapture`: 成功と解析失敗のEvidence保存を分岐。保存失敗でも正常Recordを残す。
- `ActivityProperties`: `rei.activity.keep-screenshots=false` と `keep-on-extraction-failure=false` を追加。
- `ScreenshotPersistencePolicy`: duplicate、retention、成功/失敗の独立した保存設定を判定。
- `PngScreenshotEncoder`: PNGを明示的なRAMキャッシュでエンコードする共通処理。
- `VisionActivityExtractor` / `FileScreenshotStore`: 共通encoderを使用し、ImageIOの隠れた一時画像を防止。

既定では通常時も失敗時もScreenshotStore.saveを呼ばない。
`keep-screenshots=true` は成功時だけ、`keep-on-extraction-failure=true` は解析失敗時だけ保存する。
`screenshot-retention-days=0` は両方の保存を禁止する。既定retentionは引き続き3日。
失敗Evidenceも既存Retentionを使用する。後から両保存設定をfalseにしても既存画像の期限処理は継続する。
除外、foreground切替、pause/closeは保存より優先。Capture自体の失敗ではEvidenceを保存しない。

### SSD / SQLite / ログの確認

duplicate判定前の**画像書き込みはない**。Vision送信にも画像temporary fileを使用しない。
PNGエンコードの一時キャッシュもRAMに固定した。PNG形式を維持し、画質を変更していない。
画像参照は既存の空リスト表現を利用し、Record/SessionのDBスキーマ変更は不要。

SQLiteのpayloadは型付きRecord/Sessionのみで、画像BLOB、Base64、リクエスト全文、
未検証のレスポンス全文は入らない。実際のDBとEvidenceディレクトリを用いたテストでも確認した。
Activity正常capture/duplicateの大量INFOログはもともとなく、追加していない。
エラーログはステージと例外型だけ。生のprovider payloadをログに含めないことも検証した。
既存の小さなLLM lifecycle / Tool eventsを維持する。

**対象外として残るI/O:** 構造化Record/Sessionの既存DB書き込み、Agent lifecycleの記録、
Win32 foreground metadata用の小さなJSON一時ファイル（画像ではなく、finallyで削除）。
従って全disk writeがゼロではない。日次Session再構築の差分更新も今回変更していない。

### TDDと追加テスト

保存設定の未実装Redから開始し、続けて既定保存・失敗Evidence・保存障害・ImageIOキャッシュに
関する5件の振る舞いの失敗を確認してから実装した。
保存ポリシーと共有PNG encoderへ責務を整理してGreenを確認し、全テストを実行した。

追加は22件（Activityパッケージは50件から72件）。
保存順序、duplicate tick単独のsave/Vision 0回、設定の独立性、除外/切替/pause、
画像encode/persistence failure、画像なしのSQLite/Timeline/Summary、成功/失敗Evidenceのretention、
PNG画素維持、内部ディスクキャッシュ禁止、ログへのpayload非出力を検証する。
実画面の取得・外部Vision APIへの送信はテストで行っていない。

Memory-First最終コードの全Javaテストは **2025件成功（失敗・エラー・skipは0）**。
Client単体 **41件成功**、Rust **64件成功**。JavaはJDK25、テスト専用
`REI_DATA_DIR=target/activity-test-data` と既存ローカルMaven repositoryを使用した。
既存sqlite-vec結合テストに必要なダウンロードのみ実行権限を付与して検証した。
ブラウザE2EはJava全件終了後に1ワーカーで実行し、**12件すべて初回で成功（49.7秒）**。
今回のE2Eではタイムアウトや再試行はなかった。`git diff --check` も成功。

詳細な設定表、ライフサイクル、プライバシー、書き込み抑制の範囲を
`docs/activity-timeline.md` に追記した。

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

## Phase 1〜3 初回テスト結果（082f744）

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
