# Activity Timeline Phase 3.8 — Daily Summary Synthesis

## 目的と入口

`/activity summary` を区間一覧から「その日のおおまかな流れ」へ変更した。
`/activity` 本体は Timeline のまま。保存済みの ActivityRecord、細粒度 ActivitySession、
SummarySegment と既存 TrendSummarySegment API は削除・書き換えない。

対応するコマンドは従来と同じ。

```text
/activity summary
/activity summary today
/activity summary yesterday
/activity summary YYYY-MM-DD
```

日付解決と現在時刻は一回だけ固定する。Activity の設定 zone を使用し、過去日は現地の翌日0時まで、
today は現在時刻までの半開区間。DST の23/25時間日も実際の Instant 差を用いる。
不正形式・存在しない日付・未来日は従来どおり usage / error。データなしでは
「YYYY-MM-DD の Activity は記録されていません。」と返し、LLM を呼ばない。

## 既存処理と新しい処理

旧処理は保存済み records / fine sessions → SemanticSessionPolicy → SummaryGroupingPolicy →
SummarySegment → TrendSummaryPolicy → TrendSummaryFormatter であった。
TrendSummaryFormatter は全 TrendSummarySegment に一行ずつ文章を出し、長い日は数十項目になっていた。

新しい Slash Command の経路:

```text
保存済み ActivityRecord + ActivitySession
  → 既存 SummarySegment
  → DailySummaryAggregator
  → DailySummaryAggregate
  → DailySummaryWriter（文章化のみ、失敗時はコード生成）
  → DailySummary
  → DailySummaryFormatter
```

互換性のため ActivityTimeline.trendSummary(day) の既存入口を維持し、内部で日次生成へ委譲する。
trendSegments、summarySegments、summaryBetween、summary、自然言語 ActivityTools は従来の粒度を維持する。
今回変わるのは Slash Command の summary 出力だけである。

## Aggregate と計算

DailySummaryAggregate は生の証拠を持たない、上限付きの入力モデル。

| フィールド | 意味 |
| --- | --- |
| targetDate | 対象のローカル日付 |
| observedSeconds / unobservedSeconds | 対象区間中の観測秒数／未観測秒数 |
| categorySeconds | primary category の排他的な観測秒数（unknown を含む） |
| entertainmentSeconds | 保存済み disposition ごとの観測秒数 |
| topProjects | 正規化後の primary project 上位5件と秒数 |
| services | サービスを意味カテゴリへ置換した上位3件。表示の共起秒数であり操作時間ではない |
| timeOfDay | 深夜0–6、午前6–12、午後12–18、夜18–翌0時 |
| dominantThemes | project + 開発／調査／文書作業など、上位5テーマ |
| leisureActivities | 明確な ENTERTAINMENT に限る上位3活動 |
| majorWorkBlocks / majorLeisureBlocks | 観測合計15分以上の代表区間、各上位3件 |
| projectSwitchCount / frequentProjectSwitches | 隣接する既知 project の切替数。10回以上で「多い」 |
| unknownRatio | primary category 不明秒数 / 観測秒数 |
| sourceSegmentCount | 入力 SummarySegment 数 |

SummarySegment の証拠参照を record ID で重複除去し、対象日・元 Segment の境界へクリップする。
次の観測開始で前の観測の終了を切り、二重に時間を数えない。
保存済み durationEstimate の範囲だけを数え、セグメント間の空白や未観測時間を活動へ足さない。
primary category の合計は observedSeconds と一致する。
roles は既存 ActivityRolePolicy と設定の confidence threshold により保存済み推定から復元する。
classification rules の再実行や Vision の再解析は行わない。

各時間帯の dominant は観測秒数の上位2カテゴリ（その区間の15%以上）。
時間帯ごとの作業テーマも観測秒数上位2件（15%以上）に制限し、各時間帯の project を保持する。
secondary は表示候補の意味カテゴリから1件（20%以上）、background は1件まで。
secondary / background / services は重複表示を含むため、primary category と足し合わせない。
時間帯境界をまたぐ観測は各時間帯へ秒数を配分する。観測のない時間帯は表示しない。
コード fallback は、観測はあっても dominant が不明な区間を無理に文章化しない。

major block は同じテーマ・disposition側の区分・continuityId が続き、gap が120秒以内の場合に連結する。
gap 自体は観測秒数に加算しない。本文へ全区間を列挙せず、LLM の傾向判断材料にのみ渡す。
project 切替は前の既知 project から300秒以内の変化を数え、長い未観測区間をまたぐ切替を誇張しない。

## Project alias とサービス名

ProjectNameNormalizer は表示専用。case、NFKC、空白・underscore・hyphen の表記差を正規化する。
編集距離による fuzzy merge は行わない。livevingo / livetrans / palabra は別 project のまま。
ファイル拡張子や project.cd などの記号を含む識別子、repl / suggest-rules、Factory 等の実装名、
一般的アプリ名はテーマの project 名から外し、「開発」などへまとめる。
明示的 user alias はこの自動フィルタより優先する。

設定例（application 設定）:

```yaml
rei:
  activity:
    summary:
      llm-enabled: true
      timeout-seconds: 30
      project-aliases-file: activity/project-aliases.yaml
```

alias ファイルは REI_DATA_DIR 基準（絶対パスも可）。

```yaml
projectAliases:
  sensevoice-input:
    - sensevoiceinput
    - sensevoice
    - sensorvoice-input
    - sansvoice-input
  rei:
    - rei-dev
```

typo の既定辞書は追加しない。上の例のように user が対応を明示する。
canonical name / alias の空値、同一正規化 alias の重複、複数 canonical への割当、
正規化後の canonical 重複、YAML の重複キー、不正な型・ルートを拒否する。
最大200 canonical、各100 alias、各60文字、ファイル64 KiB。
安全な YAML loader を使用する。

既存 classification rule ファイルの schema を広げず、別の小さな alias ファイルとした。
ProjectAliasStore は summary 実行時に内容変更を読み直す。新しい watcher は作らない。
不正な再編集では前回の有効 snapshot を維持して WARN。ファイル削除・空ファイルは alias なしへ戻す。
分類・Behavior が参照する OperationalRules は読み書きしない。

X / Bluesky → SNS、YouTube → 動画・音楽、GitHub / 開発分類 → 開発・調査、
ChatGPT 等 → AI支援、とコード側で意味カテゴリに変換する。
raw application、window title、contentTitle は LLM 入力にも本文にも列挙しない。

## Entertainment、unknown、unobserved

保存済み ClassificationDiagnostics.entertainmentDisposition を使用する。
ENTERTAINMENT のみ leisureActivities / majorLeisureBlocks に入れる。
NON_ENTERTAINMENT の技術動画を娯楽として数えない。
UNCERTAIN または診断情報のない旧レコードは不確実なまま保持し、娯楽へ推測変換しない。
「主な非作業活動」は娯楽判定の表示であり、あらゆる非業務行為を推定するものではない。

unknownRatio が10%以上の場合、活動内容を十分に判断できない旨を最後に一回だけ表示する。
UNCERTAIN がある場合も、娯楽時間に含めていない旨を一回だけ表示する。
未観測は冒頭の時間数として別表示し、活動時間や集中度・操作時間として解釈しない。
画面観測に関する既存注意書きも一回のみ。

## LLM、schema、fallback

既存 LlmFeature.ACTIVITY のモデル／サーバー設定を再利用する。
画像や raw evidence を送らず、上記の集約 JSON だけを渡す。
モデルへ、計算し直さない、全入力に言及しない、代表的な傾向だけを書く、
低頻度の活動・アプリ列挙を省く、注意書きや「混在」の反復を避ける、と明示する。
Task、締切、意図、生産性、集中度や成果の推測を禁止する。

出力 schema:

```json
{
  "overview": "短い全体文",
  "timeOfDay": {
    "lateNight": "短い文またはnull",
    "morning": null,
    "afternoon": null,
    "evening": null
  },
  "workThemes": ["入力dominantThemesから選択"],
  "nonWorkActivities": ["入力leisureActivitiesから選択"],
  "trend": "短い傾向文"
}
```

overview は400文字、時間帯ごと200文字、trend は240文字まで。
workThemes は5件、nonWorkActivities は3件、各90文字まで。
配列は集約に存在する値のみ許可し、重複を拒否する。時間帯は観測のあるキーだけ許可する。
必須フィールド、型、余分なキー、null、過剰な長さ、後続 JSON を検証する。
既知の断定・注意書き反復表現も検証で拒否する。
自然文全体の意味を完全に機械検証するものではないため、モデルの誤った文章化の可能性は残る。

tools / tool callbacks / internal tool execution は無効。応答の tool call や出力打切りも失敗扱い。
maxCompletionTokens=2048。入力16000文字、受信本文8000文字に上限を設ける。
既存 LlmConversationCompressor と同様の stream.blockLast(timeout) 方式で timeout 時に購読を解除する。
既定30秒、設定範囲1〜120秒。Capture / Behavior の worker、状態、cooldown は使用しない。

LLM例外・timeout・不正JSON・schema違反・入力機密検知などはコード生成の短い fallback に切り替える。
llm-enabled=false も同じ fallback。本文生成のための再試行や別LLM呼出しはしない。
プロバイダー／HTTP 層の既存再試行設定は維持するが、stream の全体待ち時間に上限を設ける。
成功時も失敗時も生の本文をログへ複製しない。DEBUG は segment数、project/category数、
prompt文字数・概算token数のみ。日次結果の新規永続化／cache は追加しない。

## 出力・fixture 比較

CLI は全体、時間帯別（最大4）、主な作業テーマ（最大5）、主な非作業活動（最大3）、傾向。
空の項目は出さない。出力件数は source segment 数に比例して増えない。

fixture: src/test/resources/activity/daily-summary-large-day.csv
実際の個人の画面履歴ではなく、要望に出た project 表記ゆれ・SNS・動画・unknown を含む合成データ。

| 指標 | Before | After（コードfallback） |
| --- | --- | --- |
| source SummarySegment | 72 | 72（原本不変） |
| 詳細項目／時間帯セクション | 60区間項目 | 4時間帯 |
| 出力文字数 | 3583 | 508 |
| 作業テーマ／非作業活動 | 各区間で反復 | 5テーマ／1活動 |

旧 TrendSummaryPolicy + TrendSummaryFormatter と新 formatter を同じ fixture に適用して比較した。
数値はこの fixture と fallback に対するもの。実LLMの文言・文字数を保証するものではない。
完全な比較出力は [fixture 比較](testdata/daily-summary-comparison.md) を参照。

## 変更クラス、テスト、非目標

新規: DailySummaryAggregate、DailySummaryAggregator、DailySummary、DailySummaryWriter、
LlmDailySummaryWriter、DailySummaryService、DailySummaryFormatter、ProjectNameNormalizer、ProjectAliasStore。
既存変更: ActivityTimeline の summary 出力入口、ActivityConfiguration の wiring、ActivityProperties の設定。
既存の日付Storageテストは区間行の期待を観測時間の期待へ変更し、原本・当日打切りの確認を維持した。

TDD: 先行 DailySummaryTest が未実装クラスで Red → 集約／fallback を実装して Green。
続いて writer schema、timeout、alias再読込、日付コマンド、fixture比較、DST／200セグメントを検証した。
通常 Timeline、Phase 3.4、Phase 3.5 Behavior、Phase 3.7.x classification の既存テストも全体実行で確認する。

この作業の branch は feature/activity-daily-summary。branch の起点は 8a21876
（Behavior会話履歴の merge を含む）。実データへの自然発火・外部LLMへの送信は行わず、
fixture と実 Picocli Command、mock ChatModel stream で確認する。

非目標: Weekly/Monthly、Productivity score、Task/Calendar、Phase 4 context integration、
LLMによるalias自動保存、raw activity／screenshot／Vision再解析、分類ルール変更、専用verbose option。
最終テスト結果・コミットは追記および作業完了メッセージを参照。


## 最終検証（2026-09-25）

追加テスト: 28ケース（DailySummaryTest 12、DailySummaryWriterTest 9、DailySummaryIntegrationTest 5、ProjectAliasStoreTest 2）。
既存の日付指定16ケース＋Storageケースも成功。追加ケースはすべて成功した。

| 対象 | 結果 | ログ（target 内、非コミット） |
| --- | --- | --- |
| Java 全体 | 2381件中2379成功、2件失敗、error/skipなし | daily-summary-java-final.log |
| Client 全体 | 42 / 42 成功 | daily-summary-client.log |
| TypeScript / Vite build | 成功 | daily-summary-client-build.log |
| Rust 全体 | 66 / 66 成功 | daily-summary-rust.log |
| E2E 初回 | 10成功、2件ページ読み込みtimeout | daily-summary-e2e.log |
| E2E 失敗分再実行 | desktop / mobile の2件とも成功 | daily-summary-e2e-retry.log |

Java の失敗は既存 WebBoundaryTest の heartbeatHasNoSequenceAndDisconnectReleasesListenerAndTimer と
sendIOExceptionUnsubscribesAndDoesNotCancelRun。SSE listener 数の期待値との差であり、前回実装で
変更前 0f67897 でも同じ失敗を再現済み。今回、このテストおよび対象 SSE 実装は変更していない。
本件に関する前回の切り分けは [Behavior 会話履歴の検証記録](behavior-conversation-history.md) を参照。

E2E の timeout は text and events are visible inline in output order の page.goto（30秒）で発生した。
失敗分だけを --workers=1 で再実行し2件とも成功。初回の失敗は flaky として記録し、全件初回成功とは扱わない。
実装ファイルは Java Activity とドキュメントのみで、Client/Rust のコードは変更していない。

実コマンド確認は fixture + Picocli で引数なし/today/yesterday/過去日を実行し、DailySummaryWriter 到達を確認。
通常の /activity today では writer を呼ばず、store.append も呼ばないことを検証した。
LLM は mock stream の structured output / malformed JSON / timeout cancellation を検証し、外部APIへは送信していない。
28ケースのほか、既存の Activity/Behavior/classification、会話/context、Web 等を全体テストで実行した。

コマンド: JDK 25 で mvnw -o -Dmaven.repo.local=F:/project/rei/.m2/repository test、
npm test、npm run build、cargo test、npm run test:e2e。
REI_DATA_DIR は F:/project/rei/target/daily-summary-test-data に隔離した。
TDD の最初の失敗は target/daily-summary-red.log、段階的確認は daily-summary-green.log / daily-summary-targeted.log。
仕上げ時に時間帯ごとの project theme テストを追加し、daily-summary-buckets-red.log で Red を確認してから実装した。

残課題: 既存WebBoundaryTest 2件、E2Eのページ読み込み揺らぎ、実運用LLMの文章品質確認。
非トランザクションの履歴移行や日次cacheは本件では実施していない。コミットIDは作業完了メッセージ参照。

最終 Java 実行の Activity / Behavior / classification 関連は374ケースすべて成功。追加28ケースも全件成功。
