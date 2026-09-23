# Activity Timeline Phase 3.7.1 — Evidence Classification Operations

Phase 3.7.1で外部ルール、atomic hot reload、Unknown/Entertainment Registry、診断、LLMによる未適用候補提案を追加した。
設定・rule schema・娯楽判定・コマンドとPrivacyは[Classification Toolkit](activity-classification-rules.md)を参照。
以下の先行保存・Vision fallback・memory-firstの構成は維持する。

既定の判定経路を、画像→Vision成功→保存から、OS情報→ルール判定→即時保存→必要な場合だけVisionで補足へ変更した。Activity自体の既定値は引き続き無効。

## 処理と主要クラス

```text
ActivityCapture (modeで経路を選択)
  → ActivityEvidencePipeline (観測worker)
    → DesktopActivityObserver.metadata / WindowsDesktopActivityObserver
    → ActivityEvidenceSource (project / recent agent events)
    → ActivityEvidenceAggregator → ActivityEvidence
    → ActivityClassifier / WindowActivityRules → ActivityClassification
    → SqliteActivityStore.append (この時点で保存済み)
    → 必要時だけRobotのRAM画像を取得
      → 前面worker: 実行1件 + 最新待機1件
      → 背景worker: 明示的に有効な場合のみ、実行1件・待機なし
      → VisionActivityExtractor → 同じID・取得時刻でreplace
  → 既存Session / Role / Summary / Behaviorのprojection
```

前面・背景のworkerを維持する。遅延するVisionを観測workerでは待たない。待機画像を置き換えても、対応するOS観測は既に保存されている。画像を待機用ファイルに書かない。高確信度の観測では、画像保存の明示指定や背景解析の明示有効化がない限りRobotも呼ばない。

## Evidenceと役割

`ActivityEvidence` は取得時刻、前面process/PID/title/windowId/bounds、可視window一覧、projectName/projectId、直近のtool種別・projectId・時刻、直前の活動履歴を分けて保持する。履歴にはさらに過去のEvidenceを入れず、再帰的な肥大化を防ぐ。

Windowsでは専用の読み取り専用PowerShell/Win32 probeを使う。`GetForegroundWindow`、`EnumWindows`、`IsWindowVisible`、`IsIconic`、`GetWindowRect`、`GetWindowText`、PID、`MonitorFromWindow`/`GetMonitorInfo`を使用し、DWMのcloaked windowも除く。可視一覧は最大32件。最小化・非表示・画面外・除外対象タイトル/processを保存対象から除く。タイトルとOS属性のみを取り、UIAの入力欄内容、キー入力、マウス、URL、ブラウザー履歴は取得しない。

「可視」はWin32上の可視属性と画面との交差を意味する。他ウィンドウで完全に覆われた領域まで厳密に判定するものではない。背景アプリの存在は補助候補であり、そのアプリを操作している証拠にはしない。モニター名はOSのdevice名。画像を取得しない記録の既存`observations`配列は空で、OS boundsは`foreground`/`detection.evidence`に保持する。分類候補の`monitor="foreground"`は前面を表すラベルで、実モニター名の推測ではない。

Project sourceは既存`ProjectService.currentContext()`を利用する。観測workerには対話client scopeがないため通常は起動ディレクトリのprojectとなる。任意の別clientの選択projectを勝手に共有しない。タイトル内のproject名との一致を必要条件とし、起動projectだけで全活動を開発扱いにしない。

`ActivityAgentEvidenceSource`は既存AgentEventBusのtool完了から`runCommand`/`executeExternalProgram`をSHELL、`writeMultiFile`/`applyTextDiff`をFILE_EDITとして取り込む。最大16件・直近120秒で、引数・コマンド文字列・結果本文を保持しない。一般のターミナル全体の操作履歴を監視するものではない。各補助sourceの例外は独立して隔離する。OS前面取得自体ができない場合や除外対象の場合は従来どおり観測をスキップする。

## 分類と確信度

| 前面Evidence | 推定 | confidence |
|---|---|---:|
| Code.exe + `BehaviorEvaluator.java - rei - Visual Studio Code` | development / rei / Visual Studio Code | 0.95 |
| Code.exe、projectを抽出できないタイトル | development候補 | 0.70 |
| Firefox/Chrome/Edge + `ホーム / X` | social / X | 0.95 |
| ブラウザー + `Some Video - YouTube` | media / YouTube | 0.95 |
| ブラウザー + Bluesky suffix | social | 0.95 |
| ブラウザー + GitHub suffix | PR/commit/issueならdevelopment、一般ページはresearch候補 | 0.85 / 0.65 |
| 汎用ブラウザータイトル、汎用Terminal | unknown（applicationは既知） | category 0 / application 1 |
| タイトルのproject一致 + 同projectの直近tool + Terminal | development / project | 0.90 |
| 未対応process | unknown（applicationはOS由来） | category 0 / application 1 |

これは校正済み確率ではなく保守的なルールの重み。browser名だけでサービスを断定せず、processとタイトルを組み合わせる。前面の重みを採用し、背景の候補数でconfidenceを加算しない。履歴は補助根拠として最大0.4を記録するが、タイトルが同じという理由で過去のVision判定を高確信度として再利用しない。背景候補が前面のprimaryを変える場合は補足しない。

`ActivityClassification`に推論、confidence、根拠source、sourceごとの重み、rule reasonを保持する。Phase 3.7では`ActivityFieldConfidence`でcategory/application/service/project/contentを独立管理する。欠落は0、OS由来applicationは1。全体confidenceは`min(category, max(application, service))`。背景候補は`secondaryConfidence`に分離し、前面confidenceへ加算しない。

usableはcategoryが既知かつ`skip-vision-confidence`以上、applicationかserviceが0.8以上。project/contentの欠落だけではVisionを呼ばない。completeは5軸すべて既知、partialはapplicationかserviceが既知でcompleteでないもの。usableとpartialは両立する。unknownは正規化後のcategoryがunknownであることを指し、低確信度の既知カテゴリとは区別する。

`BrowserTitleRules`はprocessをブラウザーに限定した優先順位付きregistry。X/Twitter、YouTube/Music、Amazon、Bluesky、Google News、GitHub、ChatGPT/OpenAI、Google/Bing/DuckDuckGo検索を扱う。GitHubのPR/commit/issue文脈だけを高確信度developmentとし、owner/repoだけの候補は低確信度。ChatGPT/OpenAIはserviceのみ確定し、categoryはunknown。単にYouTubeなどを本文中に含むタイトルをサービスと断定しない。browser suffixは前処理で除去する。

最終的なprimary/secondary/backgroundは既存`ActivityRolePolicy`が前面process/titleで照合する。Behavior用のprimary confidenceはこの役割付けで減衰するため、Vision skip判定のconfidenceとは同じ値ではない。

## 設定とVision

```yaml
rei:
  activity:
    enabled: true                 # Activity自体の既定値はfalse
    capture-interval-seconds: 60
    vision-image-scale: 0.5
    background-analysis-interval-seconds: 300
    keep-screenshots: false
    keep-on-extraction-failure: false
    detection:
      mode: evidence-first
      evidence-enabled: true
      vision-enabled: true
      fallback-enabled: true
      skip-vision-confidence: 0.8
      foreground-crop: true
      background-full-screen-enabled: false
      max-output-tokens: 2048
```

上記detection配下はすべて既定値。**usableならskip、それ以外はfallback候補**とする。`extraction-enabled: false`または`vision-enabled: false`はVision全体を停止し、Evidenceの記録は続く。`fallback-enabled: false`は前面fallbackを止める。`evidence-enabled: false`はOS取得は残してルール分類をunknownにし、Vision fallbackへ進む比較・診断用設定。

`foreground-crop: true`では既存の物理座標・DPI対応切り出しをRAM上で実行し、さらに0.5倍に縮小する。前面boundsが不明・画面と交差しない場合は全画面への暗黙のfallbackをせず、OS観測を残して画像解析をスキップする。`foreground-crop: false`は明示的な全画面送信になる。前面が画像取得前後で変わった場合も画像解析をスキップする。

背景full-screen Visionは既定で無効。背景アニメーションだけではリクエストを出さない。明示的に有効にした場合のみ最初の観測と300秒以上経過した観測で要求でき、実行中の追加要求はskipする。Evidence-first経路ではこの診断用背景解析に画像差分判定を要求しない。背景結果は同じ観測を補足するだけでprimaryを変更しない。

`mode: vision-first`で従来の画像差分・重複再利用経路へ戻せる。この場合も新しい背景既定無効・Vision上限は適用される。旧来の背景動作を再現するなら`background-full-screen-enabled: true`も必要。COMPAREモードは実装していない。

Visionモデル・endpointは従来の`rei.llm.features.activity`を使う。Activityの実リクエストでは`max_completion_tokens=2048`を指定し、従来の`max_tokens`を取り除く。**`detection.max-output-tokens`が共通/featureの出力予算に優先する。** 既存ファイルに1024が明記されていれば、新しい既定値では上書きされないため2048へ変更する。

前面は`ForegroundActivityParser`と`foreground-classification.schema.json`を使用する。単一objectの必須7キーは`category, application, service, projectCandidate, contentCandidate, summary, confidence`。categoryは既存列挙値、confidenceは0〜1。application/service/projectCandidate/contentCandidateはnull可で文字数上限64/64/80/120、summaryは160。余分なキー・末尾JSONを拒否する。observations、activities配列、monitor、座標をモデルに生成させず、monitorは送信画像の値をローカルで付ける。短い前面専用promptを使い、schema全文のsystem promptへの重複挿入を除いた。tool禁止・画像内命令を実行しない制約は維持する。背景は既存schemaを維持する。

output_limitの即時retryは行わない。timeout/validationも同じ観測への自動再解析を行わず、次の観測は通常どおり処理する。HTTP transportの既存retryとは別の方針。Phase 3.6では1024 completion tokens、本文0文字の打ち切りが見られたため、内部reasoningで予算を消費した可能性があるが、旧ログだけでは断定できない。今回`reasoning_tokens`（APIが返す場合）と`max_output_tokens`を追加した。2048でも改善しない場合は4096との比較対象にできるが、サーバー側reasoning予算を変更する実装ではない。

`/config init`で新規生成するテンプレートにもdetection設定を追加した。既存設定ファイルを自動で書き換えない。

## Provisionalと保存

Visionが必要な観測は`PROVISIONAL`、不要なら`FINAL`として先に保存する。`detection`にはEvidence、classificationSources、visionUsed、classificationMode、status、sourceConfidence、reasonを保持する。既定のmode表記は`EVIDENCE_ONLY`、成功した補足は`EVIDENCE_PLUS_VISION`。

前面Visionは`ActivityEnrichment`で各軸の確信度を比較して補足する。applicationはOS由来を維持し、Visionの欠落で既知serviceを消さない。異なるserviceへの変更はより高いservice確信度が必要で、その場合は旧serviceのcategory/project/contentを引き継がない。Visionの失敗はpartialを含むEvidence分類を残し`VISION_FAILED`を付ける。`visionUsed=true`はAPIを試みたことを表し、成功の保証ではない。画像取得失敗、前面変更、待機置換、pauseなどで呼ばれなかったものは`PROVISIONAL`のまま残る。この状態は「必ず後で解析される」という予約を意味しない。

enrichmentは元ID・capturedAt・durationEstimateを変えずSQLiteのRecordと当日のSession projectionを同じトランザクションで更新する。背景用Recordを追加せず、時間を二重加算しない。pause/close後に届いた応答は反映しない。

Visionが失敗しても、OS取得に成功した観測時間は残る。unknownはBehaviorの「観測成功」に含むが、娯楽・評価対象の分母には加えない。OS前面取得不能、除外、観測workerの過負荷、DB保存不能まで保証するものではなく、未取得時間を推測で埋めない。

従来JSONの`detection`欠落はnullとして読み込める。Phase 3.6の`detection`に新しい`fieldConfidence`/`secondaryConfidence`がなくても読み込める。既存のraw Record/FineSession/SemanticSession/SummarySegmentの入口とBehavior evaluatorを維持する。Summaryは既存projectionで再計算するため、モデルsummary全文を表示へ転記しない。

PNGの一時保存、JPEGへの変換、画像ディスクcacheは追加しない。既定では画像ファイルは作らない。Windows probeの小さなテキストJSON一時ファイルは従来どおりfinallyで削除する。構造化Evidenceを毎観測保存するため、メタデータ分のSQLite書込みは増える。

## 計測と確認

`Activity detection metrics`はプロセス内累積値として`observations`（append成功）、`evidence_only`（前面fallback不要）、`vision_fallback`（fallback必要）、`vision_skipped`、`foreground_vision`、`background_vision`、`vision_success`、`vision_failure`、`vision_timeout`、`vision_call_rate`を出す。fallback必要数と実API数は待機置換等で異なる。`evidence_only`も背景の診断設定を有効にすれば後からVision補足されることがある。call rateは(前面+背景の試行数)/保存観測数で、背景有効時は1を超え得る。再起動でリセットする。

`Activity evidence saved`の`classification_ms`はOS取得・集約・分類・初回保存までを含む。`Activity enrichment timing`の`vision_ms`は画像切り出しから補足保存まで。従来の`Activity vision timing`でinput_prepare_ms / llm_roundtrip_ms / output_parse_ms / token数を分離できる。例外ログは型・段階のみでタイトルや応答全文を出さない。

再起動後は高確信度のX/VS Code等と汎用ブラウザーを交互に観測し、metrics、保存件数、`/activity behavior evaluate`を確認する。実APIの速度・call削減率はモデルと利用画面に依存する。自動テストの待機やmockの時間を実APIのlatencyとして扱わない。

`Activity classification metrics`はunknown/partial/usable件数と率、evidence_only_rate、vision_success_rate、vision_output_limit、vision_validationを追加する。分類件数は保存後のenrichmentで差し替え、同じ観測を二重に数えない。unknown/partial/usable率とevidence-only率の分母は保存観測数、Vision成功率の分母は完了した成功+失敗数。partialは失敗率ではない。DEBUGではrule ID・各軸confidence・usable/complete/skip判定を出すが生タイトルや応答本文は出さない。

従来のEvidence/worker/背景/SQLite/Behavior/Summary互換テストに加え、`ActivityClassificationTuningTest`、`ForegroundActivityVisionTest`、`ActivityTuningPipelineTest`、`ActivityTuningMetricsTest`と旧Detection JSON読み込みテストを追加した。合成fixtureは`classification-tuning-fixtures.json`の21件。7キーschema、2048上限、reasoning token計測、service保持、失敗分類、partial/usable、誤検出抑止を検証する。mockの1024打ち切り→2048成功はリクエスト設定の検証で、実APIの成功率ではない。比較結果・全テスト結果・未検証事項は[実装報告](activity-timeline-implementation.md)を参照。
