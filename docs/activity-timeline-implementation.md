# Activity Timeline 実装報告

## Activity Timeline diagnostics（2026-09-25）

### 保存状況と最小拡張

既存Record.DetectionにはclassificationSources、visionUsed、classificationMode/status、sourceConfidence、
fieldConfidence、ClassificationDiagnostics（winning/matched rule、usable、Vision required/unknown理由）が保存されていた。
ただし `visionUsed` と `VISION_FOREGROUND/BACKGROUND` は失敗・成功未採用でも設定され、採用判定には使えなかった。
これらの既存フィールド・分類結果・分類telemetryの意味を変えず、nullableな `visionDiagnostics` を追加した。
新しい画像解析は前面/背景別にattemptを記録し、既存mergeの前後の分類値/confidence差分でUSEDを判定する。
背景は候補追加を判定する。既存 `ActivityVisionFailure` を失敗理由に利用する。新規画像・prompt・response保存はない。
`ActivityEnrichment` と `ClassificationToolkit.decorate` は新しい診断情報を引き継ぐ。

既存Behaviorの永続化はcheckpointと直近100件のassessment transitionのみ。
lastNotificationAtはLLM前の予約時刻で、publisherへの発行成功や本文・抑制結果は履歴化されていなかった。
`BehaviorTimelineEvent` を追加し、`BehaviorStateStore` のappend/range query経由でSQLiteの
`activity_behavior_events(id, occurred_at, payload)` と時刻indexへ保存する。既存テーブル変更やデータ再生成は不要。
テーブルは初回保存時に作成し、旧DBの一覧読込だけでは作成しない。旧checkpoint/transitionを通知履歴へ変換しない。
新イベントは本文を持たず、id・時刻・severity・既存Reason trigger・EMITTED/SUPPRESSED/FAILED・理由・既存観測指標のみ。
NONEとmanual evaluateは記録しない。新イベントの自動削除は行わない。
履歴保存の失敗は通知policyに影響させない。publisherとDBの分散transactionは導入せず、発行後の履歴欠落はあり得る。

### Query / presentation

`ActivityTimeline.timelineSegments` は既存日付解決とzone・SummaryPolicy/Groupingを使い、対象日のRecordを1回取得する。
細粒度Sessionの参照IDが不要な一覧ではSession個別問い合わせをせず、N+1を避ける。
`ActivityTimelinePresentationService` は同じ `[ローカル00:00, 翌日00:00)` のBehaviorイベントを一括取得し、
sealed `ActivityTimelineEntry`（ActivityEntry=interval、BehaviorEntry=instant）をtimestamp昇順に安定ソートする。
同時刻はActivityを先に表示し、区間を人工分割しない。`ActivityEvidenceDisplayFormatter` がcanonical source
WINDOW_METADATA / FOREGROUND_VISION / BACKGROUND_VISIONと診断テキストを担当する。
旧データの画像採否はUNKNOWNとし、推測でUSEDにしない。保存済みraw source名はverboseに残す。

`ActivityCommand` は日付と `--verbose` をpresentationへ渡すだけ。pause/resume/behavior/classification/summaryは従来の経路。
`ActivitySummaryFormatter.formatEntry` を抽出して既存ラベルを共有する。既存Summary生成・自然言語Tool・
Phase 3.5 evaluator/policy・Phase 3.7.xルールの判定処理は変更しない。
通常はEvidenceとEMITTED通知、verboseは観測単位のconfidence/rule/Vision結果とSUPPRESSED/FAILED・trigger/指標を追加する。

### TDD

既存CLIに対するverbose4ケースの失敗を先に確認し、formatterとBehavior履歴の未実装APIでもRedを確認した。
Window/前面/背景の使用・不使用・失敗、旧JSON、新診断のround trip、時系列merge、severity、suppression、
通知予約と発行時刻の区別、保存失敗の分離、日付・verboseの位置、Summary非影響、一括queryを検証する。

検証結果:
- 追加25ケース成功。Activity関連348件成功（既存Summary/Behavior/Classificationを含む）。
- Java全2338件: 2336成功、2失敗、0エラー。専用REI_DATA_DIRとローカルMaven依存キャッシュを使用。
  失敗は前回から確認されている変更範囲外の `WebBoundaryTest.heartbeatHasNoSequenceAndDisconnectReleasesListenerAndTimer` と
  `sendIOExceptionUnsubscribesAndDoesNotCancelRun` の購読数期待値不一致。
- client 41件、Rust 64件、E2E（1ワーカー）12件成功。
- 一時SQLiteにWindow-only / 前面使用 / 前面+背景使用 / Vision failure / emitted / suppressedを保存し、
  実際のpicocliで `/activity`、`/activity today`、`/activity --verbose`、`/activity today --verbose` を実行して表示を確認。
  手元の `.rei/memory.db` はread-onlyで確認したがActivityテーブルがなく、稼働実データによる確認は未実施。

## Activity Summary の単一日指定（2026-09-25）

`/activity summary`、`/activity summary today`、`/activity summary yesterday`、
`/activity summary YYYY-MM-DD` に対応。引数なしは today と同義。
`ActivityCommand.SummaryCommand` に日付引数・usage・today/yesterday補完を定義し、余分な引数はpicocliが拒否する。

`ActivityDateArgumentResolver` は null / 空文字 / today / yesterday / 厳密なYYYY-MM-DDを
`LocalDate` に解決する。存在しない日付は利用可能形式付きの入力エラー、未来日は日付付きの入力エラー。
`ActivityTimeline.trendSummary` が既存Clockを一度だけ固定し、resolverと `ActivityQueryRange.forDate` に渡す。
timezoneは既存 `rei.activity.zone` を使用し、システム既定timezoneには依存しない。
当日は `[ローカル00:00, now)`、過去日は `[ローカル00:00, 翌日00:00)`。
翌日開始は日付とZoneIdから算出し、DSTに対応する。午前0時ちょうどは空範囲としてDB問い合わせを省略する。

保存済みRecord / 細粒度Sessionから既存SummarySegment → TrendSummarySegment → TrendSummaryFormatterを再利用する。
Summaryの永続化方式は追加せず、従来どおり読み取り時に構築する。Vision・Activity Extraction・画像再解析・DB更新は行わない。
Phase 3.4のlabel normalization / grouping / project / mixed / 未観測時間の処理は変更しない。
日付見出しを追加し、データなしは「YYYY-MM-DD の Activity は記録されていません。」で正常終了する。
既存 `/activity today` / yesterday / YYYY-MM-DD と自然言語Tool、日単位の `trendSegments` APIは従来の範囲・表示を維持する。

TDDではコマンド・範囲テストの16ケース中12件のRedを確認してから実装した。
追加テストは日付形式、未来日、余分な引数、空データ、当日終端、過去日終端、UTC/Tokyo、DSTの23/25時間、
午前0時の空範囲、SQLiteの23:59:59.500/翌日00:00境界、未来timestampの除外、保存データ保持、既存詳細表示、補完を検証する。

検証結果:
- 追加26ケース成功。Activity関連323件成功（Phase 3.4の20件を含む）。
- Java全2313件: 2310成功、2失敗、1エラー。環境制限を解除し、専用 `REI_DATA_DIR` で再実行した結果。
  `ToolsTest.runCommandAutoPromotesSameLongProcessWithoutStartingTwice` のファイルロックエラーは単独再実行で成功。
  変更していない `WebBoundaryTest.heartbeatHasNoSequenceAndDisconnectReleasesListenerAndTimer` と
  `sendIOExceptionUnsubscribesAndDoesNotCancelRun` は単独再実行でも購読数の期待値不一致で失敗する。
- client 41件、Rust 64件成功。E2Eは初回2件がページ読込timeout、1ワーカー再実行で全12件成功。
- 固定Clockと一時SQLiteでコマンド・日付境界を検証。稼働アプリの実Activityデータを使った手動コマンド確認は未実施。

## Phase 3.7.1 Operational Classification Toolkit（2026-09-23）

ブランチは`codex/activity-behavior-evaluation`を継続。作業開始時の未コミット変更はなし。
Phase 3.6/3.7の先行保存・RAM画像・前面/背景worker・同一Observation enrichmentを維持し、外部ルールと運用用telemetryを追加した。

主要追加クラス:

- `OperationalRules`: built-in/userの検証、compile、priority、immutable snapshot、atomic reload。
- `ClassificationToolkit`: 診断、rules/status/registry表示、ActivityStoreとの接続、Privacyチェック。
- `ClassificationTelemetryRepository`: ActivityRecordとは別のSQLite metadata、ID upsert、集計、retention。
- `ClassificationDiagnostics` / `EntertainmentDisposition`: 各軸confidence、rule/source、Vision判断・理由と独立した娯楽判定。
- `ClassificationRuleSuggestions` / `LlmClassificationRuleModel`: 人間の確認用の構造化候補提案。自動適用なし。

`ActivityConfiguration`/`ActivityCapture`/`ActivityEvidencePipeline`、`ActivityRecord.Detection`、`BehaviorEvaluator`、`ActivityCommand`と設定テンプレートへ接続した。
ユーザー用ファイルは`<rei-data-dir>/activity/classification-rules.yaml`。未作成でもbuilt-in分類16件・娯楽5件で動作する。
同梱分類はPhase 3.7の動的抽出を継続し、娯楽ruleは別YAML。schema、priority/override、検証、reload、Registry、理由コード、metrics、提案とPrivacyは[運用仕様](activity-classification-rules.md)にまとめた。

### TDD・fixtureでの確認

ルールAPI/validation、registry・診断、提案、初回fallback metricsとretentionを失敗テストから実装した。
後方参照・繰り返すgroup等を制限し、proposalにはscopeとcontextの両方を要求する。
未知キー、空ID、重複ID、不正regex/category/disposition/confidence/priority、同順位の衝突、reload失敗時の世代保持を確認した。
既存completionテストの期待値と設定テンプレートのキー一覧を、新しいコマンド/設定へ更新した。

| fixture / 操作 | 確認結果 |
|---|---|
| Hugging Faceの未知タイトルを3回観測、各々Visionでresearchへ補足 | Registry保存上も1行・count=3・open=0・Vision成功3件・RESOLVED_BY_VISION。Observation数3のまま |
| 上記からclassification提案 | 最低サンプル数を満たす一貫した成功結果でYAML候補を生成。effective snapshot・ユーザーファイルは不変 |
| YouTube / JVM compilation deep diveを3回観測 | UNCERTAIN集約1件・count=3。文脈付きNON_ENTERTAINMENT候補を生成しても適用しない |
| 同じservice/titleでmediaとotherが混在 | 固定ルール候補のLLM呼出しを抑止 |
| ChatGPTのVisionがoutput_limit | OS観測1件を保存し、VISION_OUTPUT_LIMITを構造化診断・registryへ保持 |
| chat-researchを手動追加してpoll reload、次の観測 | researchになりVisionを省略。2観測・API試行1件・user rule hit 1件・evidence-only 1件 |
| media + NON_ENTERTAINMENT / research + ENTERTAINMENT | Behavior娯楽時間0秒 / 600秒。UNCERTAINは0秒。旧Recordのmediaは従来どおり600秒 |
| SQLite telemetryを失敗させる | 本体ActivityStoreへの保存は実行され、例外をCaptureへ伝播しない |
| 設定ファイルを削除しreload前に100回分類 | 同じcompile済みsnapshotを利用。次のpollでbuilt-inへ戻る |

これらは依頼文の例と既存fixtureを基にした合成metadata＋mock LLM、実SQLiteでの検証であり、実ユーザーの画面・実LLM応答から抽出した新しい実運用測定ではない。
当日の実unknown率・娯楽uncertain率・実モデルでの候補生成成功率は未測定。稼働中アプリを停止・再起動しておらず、ユーザー用ルールを実環境に自動追加していない。

新規テスト21件。全Java **2,261件**、client **41件**、Rust **64件**、E2E **12件**が成功。
Phase 3.5 Behavior、Summary、旧Record JSON、Phase 3.7 fixture、画像非保存・worker制限の既存回帰テストを含む。
最終確認の同順位衝突修正後に関連305件、Registryの物理集約追加後にOperational関連19件も再実行して成功した。
Maven packageが成功し、`target/rei-0.0.1-SNAPSHOT.jar`を更新した。分類Toolkit/Repositoryのクラスとrule/schemaの同梱をZIP内容で確認した。
Windowsの通常成果物名での置換制約を避け、一時POMで別名にpackageしてから通常名へコピーした。一時POMは削除済み。

### 運用上の変更と残課題

新しい観測では明示的dispositionを優先するため、用途不明のYouTube等を従来のmediaカテゴリだけで娯楽時間へ加算しなくなる。
古いRecordのcategory fallbackは維持し、reloadで過去の観測を書き換えない。
Registryは正規化keyで集計するが、高度なタイトルpattern clusteringは行わない。LLMへは高頻度・一貫した候補だけを渡すため、タイトルの変動が多い場合は手動rule作成が必要。
Ruleの意味的な同値/完全な競合判定はできず、regexの安全な部分集合と保守的検査を使う。Privacy検出も既知パターンに基づくため、既存のprocess/title除外設定を併用する。
全設定・診断の詳細は`docs/activity-classification-rules.md`、完全な手動編集例は`docs/classification-rules.example.yaml`。

## Phase 3.7 Evidence Classification Tuning（2026-09-23）

使用ブランチ: `codex/activity-behavior-evaluation`。Phase 3.6の先行保存・前面1件+最新待機1件・背景1件待機なし・memory-firstを維持した。

追加した主要クラスは`BrowserTitleRules`、`ActivityFieldConfidence`、`ForegroundActivityParser`、`ActivityEnrichment`、`ActivityVisionFailure`。
`ActivityClassifier`/`WindowActivityRules`は優先順位付きbrowser title registryへ委譲し、`ActivityClassification`/`ActivityRecord.Detection`に独立した5軸confidenceとsecondary confidenceを保存する。
`ActivityEvidencePipeline`はusable判定、各軸の補足、失敗別metricsを扱い、`VisionActivityExtractor`は前面専用schema/promptとreasoning token計測を使う。
設定既定値と新規生成テンプレートのActivity専用上限を1024から2048へ変更した。明示設定がある場合はその値を維持する。

前面schemaは単一objectのcategory/application/service/projectCandidate/contentCandidate/summary/confidenceのみ。
観測配列・座標・monitorを生成させず、短いpromptとresponse_formatで構造を指定する。背景schemaは従来どおり。
output_limit/timeout/validationは即時retryせず、取得済みEvidenceとpartialを保持する。known serviceをnullで消さない。
categoryが既知で閾値以上、applicationまたはserviceが0.8以上ならusableとしてVisionを省略する。project/content不明だけではfallbackしない。
category unknown、低確信度categoryなどusableでない観測は前面fallback候補になる。
completeは5軸すべて既知、partialはapplication/service既知でcompleteでないもの。secondaryの確信度をprimaryへ加算しない。
詳細なrule、各軸、設定、metrics、schema制約は[設計・運用説明](activity-evidence-first.md)を参照。

### 同じ入力でのBefore / After

旧HEADの分類器と新分類器に同一Evidenceを与え、Visionを呼ばずに比較した。以下のunknownはEvidence分類時点の値。

| 入力 | Phase 3.6 usable / Vision不要 | Phase 3.7 usable / Vision不要 | unknown 前→後 | fallback候補 前→後 |
|---|---:|---:|---:|---:|
| 代表合成fixture 21件 | 4 (19.0%) | 14 (66.7%) | 17→4 | 17→7 |
| 17:56–18:08の保存済み実Evidence 13件 | 2 (15.4%) | 4 (30.8%) | 11→9 | 11→9 |

実Evidenceのpartialは両版13件（新定義で再計算）。Xに加えGoogle Newsとem dash区切りのBlueskyを拾えるようになった。
generic browser 7件、generic terminal 1件、native ChatGPT 1件はcategoryを確定できず、実入力でのVision不要50%目標には未達。
21件は合成fixtureであり実運用の削減率ではない。旧Phase 3.6 fixtureと互換テストも維持した。

変更前の実運用ログは保存観測13件、Evidence-only 2件、Vision試行10件、成功3件 (30%)、失敗7件（全てoutput_limit）、最終unknown 8件、背景Vision 0件。
観測間隔は約60秒、保存duration合計780秒で、この範囲に観測欠落は見られなかった。fallback候補11件と実API10件は待機等により一致しない。
変更後の13件は保存済みEvidenceの再生であり、新版の実API試行数・成功率・output_limit・観測欠落を測定したものではない。
旧最終unknown 8件（Vision補足済み）と新Evidence-only unknown 9件は比較できない。

旧失敗は1024 completion tokensで本文が空の例があり、reasoningによる消費が疑われるが未確定。
新しいreasoning_tokens/max_output_tokensログで確認できるようにした。2048で必ず成功するとは保証しない。
稼働アプリをこの作業から停止・再起動しておらず、10〜20分の新版実API測定は未実施。再起動後の実測が残る。

### TDDと検証

分類API、軽量parser、失敗保持、実タイトル区切り、reasoning計測について失敗テストから実装した。新規テスト15件。
Java **2,240件**、client **41件**、Rust **64件**、E2E **12件**がすべて成功。
旧JSON（detectionなし・Phase 3.6の追加軸なし）、SQLite往復、同一ID補足、時間非重複、Phase 3.5 Behavior/Summaryの既存テストを含む。
実行中1件と最新待機1件、背景opt-in、失敗時の観測維持も既存テストで回帰確認した。
Maven packageが成功し、新しいforeground schemaを同梱した`target/rei-0.0.1-SNAPSHOT.jar`を更新した。
通常の成果物名での置換制約を避けるため、一時POMで別名にpackageしてから通常のJAR名へコピーした。一時POMは削除済み。
外部`application.yaml`のActivity専用上限も明示値1024から2048へ変更した。次回再起動から反映される。
分類器は実データのOS Evidenceだけを読み取り、スクリーンショットや外部APIへの追加送信は行っていない。

残課題はserviceを含まない一般タイトル、GitHub候補の曖昧さ、複数clientのproject対応、実モデルのreasoning予算と成功率。
Phase 3.7でもタイトルだけから操作・集中を断定しない。

## Phase 3.6 Evidence-first（2026-09-23）

使用ブランチ: `codex/activity-behavior-evaluation`。既存の前面・背景workerを維持したまま、
`ActivityEvidencePipeline`を追加してOS観測の保存をVisionより先に移した。
`ActivityEvidence` / `ActivityEvidenceAggregator` / `ActivityEvidenceSource` / `ActivityAgentEvidenceSource` / `ActivityClassifier` /
`WindowActivityRules`を追加し、Windowsの`metadata.ps1`、`ActivityRecord.Detection`、専用1024 token上限、設定テンプレートを接続した。

前面のprocess+titleで十分なら画像なしで保存。不十分なら前面cropで補足し、同じID・取得時刻を更新する。
Background Visionは既定無効。API失敗や待機置換で元のEvidenceを失わず、旧Record JSONも読み込める。
SQLite往復でBehavior/Summary互換と時間の非重複を確認するテストを追加した。

分類器と先行保存パイプラインはRed→Greenで導入し、出力上限と範囲不明時の全画面送信抑止も失敗テストから修正した。
従来のVision-firstのテストはmodeと背景opt-inを明示し、期待値を維持して回帰確認する。
設定、sources、confidence、制限、計測項目は [Phase 3.6詳細](activity-evidence-first.md) を参照。

検証結果: Java **2,221件**、client **41件**、Rust **64件**、E2E **12件**、すべて成功。
Phase 3.6で新規テスト23件を追加。Windows metadata probeはPowerShell構文とWin32 C#宣言のコンパイルを確認。
Maven packageも成功し、`target/rei-0.0.1-SNAPSHOT.jar`を更新した。
SQLite fixtureでは2観測・Evidence-only 1件・前面Vision 1件・背景0件・call rate 50%・合計120秒を確認。
遅延fixtureではVisionを待機させたまま4観測すべてを保存し、待機置換による観測欠落を0件とした。
これらはmock/合成fixtureによる結果であり、実APIの速度・削減率ではない。
稼働中アプリの再起動や実Vision APIでの長時間測定は未実施。Win32の実デスクトップでの可視判定、
複数clientでのproject対応、タイトルルールの適用範囲、reasoningモデルの1024 tokenでの応答率は実運用での確認事項。

以下は各Phaseの実装当時の報告であり、旧Vision-firstの既定動作はPhase 3.6で変更されている。

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

## Phase 3.1 — Gap / Primary / Summary theme

開始時のブランチは `codex/activity-timeline-session-refinement`、git statusはclean。
依頼どおり新規ブランチを作らず、Phase 3の`f4831ff`へ追加実装した。
今回のコミットIDは最終報告に記載する。

### 主要変更とポリシー

- `SessionGapPolicy`: 通常120秒、最大300秒。通常超は同じcanonical categoryと同じ既知project、
  または同じservice/applicationという強い一致が必要。結合時confidenceを0.85倍へ下げる。
  単一gapだけでなく、Segmentの累積未観測秒数も300秒以下に制限する。
- `ActivityVocabulary`: canonical categoryとservice/applicationの正規化、表示ラベルを分離。
  categoryはdevelopment / research / documentation / communication / social / media / shopping /
  monitoring / navigation / idle / other / unknown。coding等はdevelopment、Terminal / Shell /
  Web / local等はunknown。原Recordのモデル候補は監査可能なEvidenceとして保持する。
- `ActivityRolePolicy`: foreground process一致を必須とする8点、タイトルservice一致5点、content4点、
  project1点。別プロセスの候補はタイトル一致だけで主活動にならない。同点の異なる候補はunknown。
  score≥12はモデルconfidence×0.95、それ未満は×0.75。既定閾値0.5未満はunknown。
  監視候補はforegroundの裏付けがなければbackground。全画面差分を操作証拠には転用しない。
- `SemanticSessionPolicy`: canonical categoryで意味的な区間を作成。観測秒数による代表選択、
  短い往復切替の吸収を維持し、異なるprojectや累積欠測上限を回避する結合を防ぐ。
- `SummaryGroupingPolicy`: social / media / shoppingはweb-browsing、同じprojectのdevelopment /
  research / documentationはwork-developmentへまとめる。表示テーマは元categoryを書き換えない。
  同じgap policy、日付・continuity境界を守り、長いgapはテーマが同じでも跨がない。
- `SummarySegment`: themeとprimaryCategoriesを追加。観測秒数、欠測秒数、元Evidence、fineSessionIdsを維持。
- `ActivitySummaryFormatter`: 未観測率10%未満は注記なし、10〜30%は「一部未観測時間あり」、
  30%超は「観測できた時間帯のみ」「未観測の割合が高い期間」と明示。秒数は構造化値に残す。
  unknownでも確認できるsecondary / backgroundを最大3種類補足する。themeの文章に存在しないカテゴリを足さない。
- `ActivityTimeline` / `ActivityConfiguration` / `ActivityProperties`: 新しい層と設定を接続。
  `ActivitySession`には時間範囲から欠測秒数を取得する計算メソッドを追加。

設定は `rei.activity.summary-normal-merge-gap-seconds: 120`、
`summary-maximum-merge-gap-seconds: 300`、`primary-confidence-threshold: 0.5`。
従来の `summary-gap-seconds` を明示している場合は追加の上限として尊重する。
Rei→rei、Twitter / X (Twitter)→内部x・表示X、Local terminal / PowerShell等→terminal・表示ターミナル。

### データ保持とAnalytics

ActivityRecord → 永続Fine-grained ActivitySession → 役割付き意味区間 → SummarySegment → 表示。
永続化した細粒度Sessionの境界、元Recordと自由文、画像参照は変更しない。
新しいcanonical categoryは意味projectionの値であり、古い原文カテゴリを削除する移行は行わない。
Summary theme、primaryCategories、役割、confidenceは元Evidenceから再構成できる。
時間範囲はwall-clock、observedSecondsは重複を除く観測推定時間、unobservedSecondsはその差。
欠測をfocusMinutesやSNS利用時間へ加算しない。表示上の圧縮を将来のスコア計算には使わない。

### Before / Afterと件数

提示例の相対時刻・カテゴリをfixture化したテストでは、
09:52–09:53 shopping / 09:54–10:10 social / 10:11–10:16 social /
10:27–10:32 social相当の4件から2 Segmentになる。

```text
09:52–10:16 Web閲覧が中心と推定。
10:27–10:32 SNS閲覧が中心と推定。
```

前半の短いgapは許容し、後半の11分欠測は結合しない。
別の半日相当fixtureでは24細粒度Session→6 Segment（75%減）。
再現出力は `target/activity31-example.txt`。実機DB全体は読み出していないため、
実運用の平均Segment件数は未測定。6件は合成fixtureの値であり、件数上限を強制していない。
小さなgapが連鎖して欠測540秒になる例も、累積300秒の上限で分割されることを検証した。

### TDD / テスト

Phase 3.1の新API・gap・category・foreground・unknown・themeテストを先に追加し、Redを確認して実装。
unknownでbackgroundしかないと補足が出ない回帰も、失敗を確認してから修正した。
旧canonical値と集約層変更の期待値を更新し、原データ保持の検証は維持。
追加18件（`ActivityPhase31Test` 17件、`ActivitySummaryQueryTest` 1件）。
前回Java2069件、Client41件、Rust64件、E2E12件。
今回Java2087件成功（2069 + 18、failure / error / skipすべて0）、Client41件成功、Rust64件成功。
E2Eは12件すべて成功（1 worker、53.0秒）。今回flaky / timeout / 再実行はなし。
`git diff --check`成功。実画面・実Vision APIは呼ばず、実機DBも変更していない。

### 残課題

foreground processとVisionのapplication表記を対応付けられないものはunknownになる。
alias辞書と閾値は実運用データでの調整余地がある。入力操作やブラウザタブの実使用は未測定。
Summaryの件数より観測の正確さを優先するため、頻繁な欠測やproject変更があれば目標件数を超え得る。
Behavior Evaluation、お小言、Productivity Score、週次/月次分析、Adaptive Coachingは追加していない。

## Phase 3.2 — 時間帯の傾向Summary

開始時のブランチは `codex/activity-timeline-session-refinement`、git statusはclean。
新しいブランチは作成せず、Phase 3.1の`b8fc282`へ追加実装。今回のコミットIDは最終報告に記載する。

### 責務と変更範囲

Fine-grained ActivitySessionは詳細・将来Analytics用。Phase 3.1のSemanticSessionPolicy、
SessionGapPolicy、Primary / Secondary / Backgroundの判定も変更しない。
Phase 3.1の `SummarySegment` をsourceとして保持し、その上に表示専用の `TrendSummarySegment` を追加した。
時間範囲は「そこで観測された傾向」であり、同じ活動が連続したという意味ではない。

変更するコマンドは `/activity summary`。today / yesterday / 日付指定 / 引数省略、
既存の詳細query、自然言語ツールはPhase 3.1の挙動を維持する。
新しい読み取りAPIは `ActivityTimeline.trendSegments(day)` / `trendSummary(day)`。
旧summarySegments等の結果も変更しない。データ移行・DB書換え・追加LLM・画像再送信は不要。

### 主要クラス

| クラス | 責務 |
|---|---|
| TrendSummaryPolicy | 時間帯grouping、foreground evidenceに基づくproject・category・時間集計、補足ラベル選定 |
| TrendSummarySegment | continuity、theme、既知/未判定/未観測時間、全Evidence・元Segment・fineSessionIdsを保持 |
| TrendSummaryFormatter | 観測範囲を限定した活動中心の定型文。otherを有用な主活動として表示しない |
| ActivityDisplayLabels | 表示専用alias。未知の固有名詞は推測で変更しない |
| ActivityTimeline / ActivityCommand | 既存APIを維持してsummaryの新しい経路を接続 |

### Grouping / 時間 / unknown

新たな結合では全体60分以内、隣接gap20分以内、同じ日付を要求する。
単独ですでに長い連続区間は強制分割しない。上位family、project、service/application、
短い変化、unknownを材料にまとめる。SNS / media / shoppingはweb-browsing、開発 / 調査 /
文書はdevelopment-research。同じprojectの異なる作業カテゴリを結合し、異なる既知projectは分ける。
unknownを跨いでもprojectの競合は消さない。

観測推定時間は元Recordの区間をsource境界・次の観測でクリップして集計。
observedSeconds = knownSeconds + unknownSeconds、unobservedSeconds = 時間範囲 − observedSeconds。
otherは表示用のunknownSecondsへ含めるが元カテゴリは保持。
Primaryが判定できた時間が観測の半分以上なら既知カテゴリをテーマに用い、半分未満なら
可視候補を画面表示の傾向として参照する。これを実操作へ置き換えない。

- CONTINUOUS: 同系統の既知観測のみ、同じcontinuityId、最大gap120秒以下、未観測率10%以下。
- INTERMITTENT: 欠測・unknown / other・continuityId変更等を含む。
- MIXED: 複数系統の既知観測を含む。欠測秒数も別に保持する。

unknown / otherは時間帯へ吸収しても再分類しない。「主活動を判定できない時間帯もあります」と
一度補足する。全て未判定なら「主活動は判定できません」と確認できる表示名を返す。
いずれも「観測できた範囲では」と限定し、「未観測の割合が高い期間」の反復をやめた。
Analyticsはこの長い時間幅を使わず、Fine-grained Session / Recordを入力とする。

### Label normalization

browser→ブラウザ、cmd→ターミナル、gradle→ビルド、python→Python関連、shopping→ショッピング、
video→動画、openai→AIツール、chatgpt→ChatGPT、notion→Notion、asus→ASUS。
spomin dashboard等、意味不明な固有名詞はそのまま保持する。
最大4件をPrimaryとの一致、観測秒数で重み付けした出現頻度、projectとの対応の順で選ぶ。
重複は同一観測内で二重加算しない。foreground判定用aliasと表示名を分離している。

### Before / After

実機DBを読んだ結果ではなく、依頼文の時間・カテゴリを基にした**合成fixture**で検証。

- 08:03–08:43相当の9区間 → 1傾向。観測21分、未観測19分、continuity=INTERMITTENT。
- 午前07:24–11:55の35細粒度Session → 5傾向（約86%減）。開発・SNS・買い物・Slack・
  yagisan-reports文書作業とunknownを含む。35件のEvidenceは全件残る。
- unknown / knownを挟む5件をSQLiteへ保存し、傾向化後も全5細粒度Session、
  Phase 3.1のprojection、元Recordと画像参照を再取得できることを検証。

Beforeは数分ごとの「主活動は判定できません」「SNS閲覧が中心」等が35行。
Afterの出力例（先頭の全体説明は省略）:

```text
08:31–09:30 観測できた範囲では、reiの開発・確認やSNSに関する画面が断続的に見られました。
X・ターミナル・エディタ・GitHubなどが表示されていました。主活動を判定できない時間帯もあります。

11:42–11:55 観測できた範囲では、yagisan-reportsの文書作業に関する画面が断続的に見られました。
エディタ・Python関連などが表示されていました。
```

全出力はテストが `target/activity32-example.txt` に生成する。
5件という数値はこのfixtureの結果であり、実運用平均ではない。件数を達成するための削除・ハード上限はない。

### TDD / テスト

Trend型とquery APIのテストを先に追加し、未実装のRedを確認してから実装した。
Green後に時間帯・ラベル・定型文の責務を分離し、日付・continuityId・実例の時間集計テストを追加。
追加16件（ActivityTrendTest 13件、ActivityTrendQueryTest 3件）。
既存のsummaryコマンドdispatchテストは新しい経路へ更新し、他の操作・詳細API検証は維持。
Java全件2103件成功（前回2087 + 追加16、failure / error / skipすべて0）。
Client41件、Rust64件も成功。E2Eは12件すべて成功（1 worker、58.1秒）。
今回flaky / timeout / 再実行なし。`git diff --check`成功。実画面・実Vision API・実機DBへのアクセスは行っていない。

### 残課題

最大60分・gap20分は初期のgrouping基準。実履歴に応じた調整余地がある。
前面アプリを判定できない区間は今後も未判定として残る。themeも操作内容の実測ではない。
既存の長い単独区間には時間窓による強制分割を行わない。
今回の対象はsummaryコマンドだけで、自然言語ツールやtoday表示への展開は行っていない。
Behavior Evaluation、お小言、Productivity Score、週次/月次分析、Adaptive Coachingは追加していない。

## Phase 3.4: Summary表示品質と意味境界の調整

ブランチは `codex/activity-timeline-session-refinement` を継続。開始時の作業ツリーはclean。
Phase 3.2の `6882c7d` に追加実装し、Activity判定・細粒度Sessionのmerge policyは変更しない。
コミットIDは `git log --oneline --grep='polish activity trend labels'` で確認できる。

### 主要クラスと役割

| クラス | 変更 |
|---|---|
| ActivityDisplayLabels | 表示用canonical alias、表示名、Summary限定のproject候補検証 |
| TrendSummaryPolicy | 45分soft limit、内部Evidenceに基づく意味境界、project/context優先の最大4ラベル |
| TrendSummaryFormatter | 観測密度・Primary confidenceによるcontinuous / intermittent / mixedの文面 |
| TrendSummarySegment | 子のEvidence・時間範囲に対応する正確なfineSessionIdsの解決 |
| ActivityTimeline | 同じ読み取りで取得したfine sessionsを分割後の参照解決にも利用 |

### ラベルと意味役割

rawは変更せず、`WindowsTerminal` → canonical `terminal` → display `ターミナル` の順に表示だけを正規化。
case / whitespace / NFKC / 既知aliasのpunctuation差を吸収する。Text Editor / text editor / Local editorを
エディタ、Local log fileをローカルログ、Twitter / X (Twitter) / X (旧Twitter)をXにする。
canonical化した表示キーで重複除去し、同じ観測の頻度を二重に数えない。
未知の固有名詞は推測で翻訳せず、既知projectとの対応→Primary→頻度の順で最大4件を選ぶ。

project候補はforeground側に存在しても無条件では採用しない。英字始まりの2〜60文字の識別子
（英数字・`_`・`.`・`-`）であることを要求し、category、既知service/application、同じEvidence内の
service/applicationに一致する値を除外する。`rei` / `yagisan-reports` / `another_project`は保持。
`x browsing` / X / GitHub / ChatGPT / WindowsTerminal / development / 機能設計の提案はproject表示に使わない。
content/topicからprojectを作らず、不確実な場合は一般的な活動名に戻す。
これは構文・意味役割の保守的検証であり、実在projectの登録簿を確認するものではない。

### 分割

softMaximumDurationは既定45分（Javaポリシー値。YAML設定は未追加）。Phase 3.2の候補生成時の
最大60分・隣接gap20分を維持し、生成した候補内部を観測単位で再検討する。

- project switchは長さにかかわらず強い境界。unknownを跨いだ別projectへの切替も検出。
- dominant themeは前後各10分以内で観測推定秒数を集計し、各側5分以上・観測時間の70%以上を占める
  異なるfamilyの持続的変化として判定。work（development/research/documentation）と
  leisure（social/media/shopping）の境界もここに含む。45分未満でも分割可能。
- 45分超ではforeground文脈の持続的変化、5分以上の未観測gap（両側5分以上観測）、同family内の
  Primary category変更も検討。優先度はproject、theme、foreground、gap、category。
  同順位は中央寄りの境界を採用し、子も同じルールで再評価する。
- 同project内のツール変更だけでは分割しない。55分の同一傾向は1件のまま。2分のSNSへの寄り道も
  前後の開発から無理に切り離さない。分割点はサンプル境界であり45分ちょうどではない。

既存の長いPhase 3.1 SummarySegment内のEvidenceも確認する。元sourceSegmentsはそのまま親参照として残す。
子のEvidence・観測秒数を再集計し、Timeline APIは子に対応するfineSessionIdsを解決する。
元Record・細粒度Session・Raw Evidence・roles・confidence・画像参照を変更せず、DB更新もしない。
分割により子区間外となる未観測gapは、活動時間へ加算しない。Analyticsの入力は今後も細粒度Session。

### 自然文と不確実性

追加LLMは使わない。既知Primary時間が観測の70%以上、既存RolePolicyのPrimary confidenceを
観測秒数で重み付けした値が0.7以上なら活動名を使う。
CONTINUOUSで未観測率10%以下なら「〜が中心」。INTERMITTENTや未観測が多い場合は
「観測できた範囲では」「断続的に」を残す。MIXEDは「〜と〜が混在」。
confidenceが弱い場合は「〜関連の画面」へ戻し、全unknownは主活動未判定とする。
冒頭の観測・推定の説明、一部unknownの短い補足を維持する。操作、集中、バグ修正等は追加しない。

### Before / After

ユーザー提示例の問題:

```text
reiの開発・確認に関する画面が見られました。Local log file・Text Editor・text editor・GitHub…
x browsingの開発・確認やSNSやコミュニケーションに関する画面…
```

Phase 3.4では上記ラベルをローカルログ・エディタに正規化し、エディタの重複と偽project名を除去する。
以下は実機DBの再生ではなく、同種の問題を持つEvidence fixtureからの実際の出力:

```text
09:08–09:46 rei関連の開発・確認が中心。ターミナル、GitHubなどが表示されていました。

09:08–09:13 観測できた範囲では、開発・確認とSNS閲覧が混在。
ターミナル、X、GitHubなどが断続的に表示されていました。

09:08–09:38 SNS閲覧が中心。X、GitHubなどが表示されていました。
09:38–09:58 rei関連の開発・確認が中心。ターミナル、GitHubなどが表示されていました。
```

最後の例は50分の候補を持続的なテーマ変化の位置で30分＋20分へ分割したもの。
全出力は `target/activity34-example.txt` にテストが生成する。ユーザーの実データの正確な分割位置は
元Evidenceに依存するため、提示された旧Summaryの文章だけからは復元しない。

### 検証と残課題

先に16件の回帰テストを追加し、旧実装でラベル・project・内部境界・文面の失敗を確認してから実装。
確信度の高いfixtureには画面タイトルと一致するcontent evidenceを明示し、低確信度のfixtureと分離した。
さらにsoft limit変更、長いgap、unknownを挟む切替、出力例、SQLiteの細粒度参照・再読込のテストを追加。
追加21件（ActivityPhase34Test 20件、ActivityTrendQueryTest 1件）。

最終検証: Java 2,124件（failure / error / skipすべて0、2分29秒）、Client 41件、Rust 64件、
ブラウザーE2E 12件（1 worker、54.5秒）がすべて成功。flaky / timeoutなし。
TDD中の意図したRedと実装修正後の検証を除き、全体スイートの再実行は不要だった。
`git diff --check`も確認。

project検証はヒューリスティックなので日本語名・空白を含む正当なprojectは一般化される。
境界検出は5分以上の裏付けを要求するため、疎な観測の細かな変化は混在のまま残る。
自然文は決定的なテンプレートであり、自由なLLM作文は行わない。
変更対象は `/activity summary`。today / yesterday / 日付指定や自然言語ツールへの展開は行っていない。
実Vision API・実画面・ユーザーのActivity DBにはアクセスしていない。

## Phase 3.5: Behavior Evaluation / Behavior Notification

Phase 3.4が取り込まれたmain `1963275` から専用ブランチ `codex/activity-behavior-evaluation` を作成。
開始時の作業ツリーはclean。コミットは `git log --oneline --grep='add opt-in activity behavior evaluation'` で確認できる。

### 主要クラス

| クラス | 責務 |
|---|---|
| BehaviorProperties | デフォルト無効、カテゴリ、連続閾値、窓・比率・最小観測量、中断、cooldown、履歴範囲の設定・検証 |
| BehaviorEvaluator | 詳細Sessionと参照先Recordを純粋に評価。Primary観測秒数、連続時間、両window、回復時刻を計算 |
| BehaviorAssessment / BehaviorSeverity | NONE〜STRONG_WARNING、reason enum、観測秒数・比率、根拠カテゴリ・サービス・confidence |
| BehaviorNotificationPolicy | cooldown、escalation、回復・新episode、現在の活動、Chat busyの抑制判断 |
| BehaviorState / SqliteBehaviorStateStore | 1行のcheckpointと最大100件の重要な状態遷移の保存 |
| BehaviorNotification / BehaviorMessageGenerator | 通知許可後の構造化評価と発話生成の境界 |
| LlmBehaviorMessageGenerator | 既存キャラクターによる短い発話だけ。評価・境界・時間をLLMへ委譲しない |
| BehaviorService / BehaviorConfiguration | 1 worker・queue 0で評価と通知を実行し、障害・生成中の状態変化を隔離 |
| ActivityCommand | behavior on / off / status / evaluateと補完 |

関連変更はcanonical categoryのgaming補完、gamingのSummary表示、LLM feature `activity-behavior`、
MessageOrigin.BEHAVIOR、`/config init`のテンプレート。既存のチャット発話publisher / messageイベントを再利用し、
Behavior専用イベントを増やしていない。

### 評価・通知

入力はFine-grained ActivitySessionと、そのrecordIdsが参照するActivityRecord。
既存SessionがPrimary観測内訳を直接保持しないため、元Recordから既存ActivityRolePolicyで補う。
Record推定区間をSession・次の観測・現在時刻・履歴範囲へclipし、重複を除き、SessionのobservedSecondsを上限とする。
SummarySegmentは使わず、元の保存済みSessionやRaw Evidenceも変更しない。

娯楽カテゴリはsocial / media / shopping / gaming。communicationを含む他カテゴリを一律に娯楽にしない。
eligibleはPrimary既知かつunknown / other / idle以外の観測時間。unknown・未観測は分子/分母から除外する。
連続娯楽はカテゴリ内切替をつなぐが、中断・未観測秒数を加算しない。60秒までの短いノイズは許容、
長いunknown/gapとcontinuityId変更は連続性を切る。既知非娯楽5分で連続時間・episodeを回復する。

連続30/60/120分でNOTICE/WARNING/STRONG_WARNING。直近60分はeligible30分以上・娯楽50%以上でNOTICE、
直近120分はeligible60分以上・娯楽60%以上でWARNING。最大Severityを採用し、reasonをCONTINUOUS /
RATIO / BOTHに構造化する。Assessmentには両窓の秒数・比率も残す。

通知cooldownはNOTICE60分、WARNING45分、STRONG_WARNING30分。理由が変化しても同じ傾向として抑制し、
直前の通知Severityからのescalationのみ突破できる。回復通知は出さず、回復後の通知には新しい区間自身が
閾値を満たすことも要求する。昔のwindowの高比率だけで即再通知しない。
Chat busy・Capture無効/pause・現在娯楽でない・古い観測は通知しない。

予約をSQLiteへ保存してからLLMを呼ぶため、再起動・生成失敗・送信破棄でもcooldownは残る。
状態保存失敗時は通知しない。重要なstate変化だけ書き込み、履歴は最大100件。
生成中にoffやChat開始が可能で、送信直前の再評価で作業復帰等が分かれば発話を破棄する。

### 発話と設定

SystemPromptServiceのキャラクターを再利用。Severity・reason・観測時間・比率・カテゴリ・confidence等だけを
小さなJSONで渡す。低confidenceではサービス名を外す。Raw画像・タイトル・会話履歴・Task/Calendarは渡さない。
ツール・memory・advisorsを使わず、評価の変更、攻撃的表現、実操作や未完了作業の創作を禁止する指示を加える。
空・上限到達・過長・tool call・代表的な侮辱語を含む出力は配信しない。
モデル指定は `rei.llm.features.activity-behavior`。カスタムサーバーから別サーバーへのfallbackは使わない。

全既定値・設定例は [activity-behavior-evaluation.md](activity-behavior-evaluation.md) に記載。
自動評価は60秒ごと、履歴範囲24時間、enabled=false。on/offは起動中のoverride、再起動時はYAMLに従う。
statusは保存済みcooldownも表示し、evaluateは無効中でも明示的に行える通知なしdry run。
`/config init`テンプレートと設定bindingのテストも更新した。

### Fixture / TDD

SNS20分→動画10分→開発5分→unknown30秒→gap5分→SNS10分→動画15分→開発10分→SNS30分という
合成fixtureで、連続娯楽30分、60分窓50/60分（83.3%）、120分窓85/100分（85%）、WARNING / BOTHを確認。
unknown30秒・gap5分を除外し、workで連続時間がリセットされる。結果は `target/activity35-example.txt`。
実機のActivity DBは使っていない。

最初にEvaluator/Policyテストを追加して未実装のRedを確認し、純粋ロジックを実装。
次に「LLM生成中に作業へ戻る」テストの失敗を確認して、送信直前の再評価を追加した。
設定テンプレートの既存全enabledキー検査にもActivity/Behaviorの新規Java設定を追加した。

追加47件: Evaluator20、Policy6、Service12、Wording3、Configuration3、SQLite統合2、補完1。
Java全2,171件成功（failure / error / skip=0、2分18秒）。Client41件、Rust64件成功。
ブラウザーE2Eも12件成功（1 worker、46.2秒）。flaky / timeoutなし。`git diff --check`成功。

### 残課題・境界

LLMのトーン・事実忠実性を完全な意味検証で保証するものではなく、実モデルでの使用感確認は残る。
通知予約後の失敗・キャンセルではcooldownを保持するため、通知が欠けることがある。
履歴範囲を超える連続時間は取得範囲内の下限値。休憩の意図や仕事が終わったかは推定しない。
Task / Calendar / 締切 / Working Set / 未完了project / Score / 週次月次分析 / Adaptive Coachingは未実装。
実画面・実LLMへのアクセスや実通知は行わず、既存Memory-First・画像retentionを維持した。

## Behavior Notification の会話履歴

新しく emitted された通知は assistant / source=BEHAVIOR_NOTIFICATION として通常履歴・LLM context に保存します。suppressed / NONE は対象外です。同じ通知 ID を Timeline と共有し、再起動後の重複も防止します。Project/Session、圧縮、Privacy、障害時の扱いは [設計・実装記録](behavior-conversation-history.md) を参照してください。

## Phase 3.8: Daily Summary Synthesis

Slash Command の summary 出力を、区間の全件列挙から上限付きの DailySummary に変更した。
保存済み SummarySegment の証拠をコードで日次集約し、LLM は文章化のみを行う。
元 ActivityRecord / ActivitySession / SummarySegment、Timeline、Behavior / classification は変更しない。
新規クラス、alias、schema、timeout/fallback、fixture比較、全テスト結果は
[Daily Summary 実装記録](activity-daily-summary.md) を参照。

## Phase 3.8.1: Theme Enrichment

WorkThemeAggregation による canonical project 統合、保存済み topic 候補の抽出、generic 抑制と順位付けを追加。Bucket ごとの project/theme を fallback と LLM 入力へ渡す。仕様・比較・検証結果は [Theme Enrichment 実装記録](activity-theme-enrichment.md) を参照。

## Phase 3.8.2: Theme Attribution Quality

Summary側に ProjectThemeAssociation を追加し、強い関連だけspecific themeとして表現する。詳細は [実装・検証記録](activity-theme-attribution.md) を参照。
