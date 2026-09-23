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

## Phase 3 集約・要約品質改善

ブランチ: `codex/activity-timeline-session-refinement`。
既存実装を含む `codex/startup-project-option` のHEADから分岐。
コミットIDは最終報告に記載する（この文書自体を同じコミットに含める）。

### データの3層と互換性

1. `ActivityRecord`: 元のOS observations、foreground、Vision inference、confidence、changeAmount、
   durationEstimate、continuity、画像参照をそのまま保存。
2. Fine-grained `ActivitySession`: 従来のSessionMergePolicyで永続化。既存の細粒度境界、recordIds、
   inference、時間情報を変更しない。旧JSON payloadの移行・過去履歴の再書込みは不要。
3. `SummarySegment`: 役割付きSemantic Sessionを表示時に生成。primary / secondary / background、
   confidence、根拠コード、元Record全件、fineSessionIds、observedSeconds / unobservedSecondsを保持。
   DBにはSummary文字列を保存しない。

以前の表示は、同じcontinuity・foreground・候補集合、90秒以内の観測間隔という細粒度結合の後、
代表Vision文章を各行に出していた。今回その細粒度層は維持し、表示層の結合を追加した。
開発カテゴリとproject文脈を軸にアプリ切替やSecondary変化を許容し、欠測gapは推定区間の終端から
最大180秒まで許容する。A→B→AのBが120秒以内なら短い変化を表示上吸収する。
異なる既知project、継続する主活動変更、長いgap、continuity・日付境界は維持する。
二つの閾値は `summary-gap-seconds` / `summary-brief-switch-seconds` で変更可能。

### 主要クラス

| クラス | 責務 |
|---|---|
| ActivityRolePolicy / ActivityRoles | foreground process/titleとの対応から役割・推定confidenceを付与 |
| SemanticSessionPolicy | 意味的結合、短時間切替の吸収、時間クリップ、観測秒数による代表選択 |
| SummarySegment | 元Evidence・細粒度Session参照を持つ表示projection |
| ActivitySummaryFormatter | 活動中心の短い定型文、先頭1回の注意文、欠測表示 |
| ActivityTimeline | 日付・期間検索、Summaryと詳細Timelineの分離 |
| ActivityStore / SqliteActivityStore | 元Recordの読み取り専用overlap queryを追加 |
| ActivityProperties / ActivityConfiguration | 表示用gap・短時間切替の設定 |
| ActivityTools | `activitySummary` を追加。詳細取得2ツールは維持 |

PrimaryはVision文章の印象だけで決めない。process/applicationの対応、タイトルに現れるservice /
content / projectを使い、候補が曖昧な場合は未判定にする。継続時間は推定観測秒数で重み付けし、
短い候補より長くforeground evidenceがある候補を代表にする。confidenceは操作の確定度ではない。
監視画面はBackground候補だが、foregroundならPrimaryへ昇格できる。残りはSecondary。
全画面差分を個別ウィンドウの操作へ帰属させることは避け、changeAmountはEvidenceとして残した。
入力監視は追加していない。

### Summary生成とfixture結果

詳細Sessionと元Recordの検索 → recordの役割推定 → semantic grouping → bounded A/B/A smoothing
→ 元Session参照を付与 → 日本語の定型文、という流れ。追加LLMも画像再送信もない。
全文Vision summaryは表示に流用せず、上限3種類の補助表示と「など」に圧縮する。
存在しない「バグ修正」等を補わず、「開発・確認作業が中心と推定」までに留める。

依頼文の時間帯に合わせた**合成fixture**（実機DBを読み出した結果ではない）:

```text
Before: 09:08–09:09 / 09:10–09:21 / 09:21–09:39 /
        09:39–09:40 / 09:41–09:46 の5細粒度Session

After:
画面の観測に基づく振り返りです。
表示内容からの推定を含み、実際の操作・集中を断定するものではありません。

午前:
- 09:08–09:46 rei関連の開発・確認作業が中心と推定。X・YouTube Musicも並行して表示。（未観測 120秒を含む）
```

5 → 1ブロック（80%減）、観測推定36分、未観測2分。
別fixtureのTerminal / GVIMと補助サービスを交互に切り替える20 Sessionも1ブロック（95%減）。
圧縮後も全Record、元の説明、画像参照と細粒度SessionをDB再オープン後に取得できることを検証。
再現出力はテストで `target/activity-refinement-example.txt` に生成する。
1日のブロック数を強制的に5〜12に丸めることはしない。

### TDDと検証

`ActivitySemanticTest` は未実装型によるRedから開始し、8件Greenの後に境界テストを追加。
観測時間による代表選択は、長く続くmediaより先頭socialを選んでしまうRedを確認して修正。
`ActivitySummaryQueryTest` とsummary toolも未実装APIのRedを確認してから実装した。
既存のsummary文字列転記を期待したテストは、新しい表示と元の説明の保持を検証する形に変更。

追加: semantic policy / roles / formatter 16件、SQLite query / retention 3件、summary tool 1件、計20件。
Java全件は2069件成功（failure / error / skipすべて0）。クライアント41件、Rust64件成功。
ブラウザE2Eは12件すべて成功（1 worker、1.2分）。今回の全件実行ではflaky / timeout / 再実行なし。
`git diff --check` も成功。実画面の取得や実Vision APIは自動テストで呼んでいない。

### 残課題・非目標

ブラウザのタブやモニター別の入力先は観測しておらず、foregroundに対応付けられない候補は未判定。
アプリ別名・カテゴリ正規化は限定的。未知のモデル表記への対応、実機データでの閾値調整、
非常に多様な日の表示粒度の調整は今後の検証事項。今回の圧縮率は合成fixtureの値。
将来のAnalyticsは元Recordと細粒度Sessionから計算する。表示上吸収した切替やgapを
focusMinutes等へ流用しない。Behavior Evaluation、お小言、Productivity Score、週次/月次分析、
Adaptive Coachingは今回も追加しない。
