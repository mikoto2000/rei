# Activity Timeline Phase 3.6 — Evidence-first

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
| ブラウザー + 既知のGitHub/Bluesky suffix | development / social | 0.95 |
| 汎用ブラウザータイトル、汎用Terminal | unknown | 0.40 |
| タイトルのproject一致 + 同projectの直近tool + Terminal | development / project | 0.90 |
| 未対応process | unknown | 0.20 |

これは校正済み確率ではなく保守的なルールの重み。browser名だけでサービスを断定せず、processとタイトルを組み合わせる。前面の重みを採用し、背景の候補数でconfidenceを加算しない。履歴は補助根拠として最大0.4を記録するが、タイトルが同じという理由で過去のVision判定を高確信度として再利用しない。背景候補が前面のprimaryを変える場合は補足しない。

`ActivityClassification`に推論、confidence、根拠source、sourceごとの重み、rule reasonを保持する。最終的なprimary/secondary/backgroundは既存`ActivityRolePolicy`が前面process/titleで照合する。Behavior用のprimary confidenceはこの役割付けで減衰するため、Vision skip判定のconfidenceとは同じ値ではない。

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
      max-output-tokens: 1024
```

上記detection配下はすべて既定値。confidence **0.8以上でskip、0.8未満でfallback**とし、境界に別の閾値や未定義領域を設けない。`extraction-enabled: false`または`vision-enabled: false`はVision全体を停止し、Evidenceの記録は続く。`fallback-enabled: false`は前面fallbackを止める。`evidence-enabled: false`はOS取得は残してルール分類をunknownにし、Vision fallbackへ進む比較・診断用設定。

`foreground-crop: true`では既存の物理座標・DPI対応切り出しをRAM上で実行し、さらに0.5倍に縮小する。前面boundsが不明・画面と交差しない場合は全画面への暗黙のfallbackをせず、OS観測を残して画像解析をスキップする。`foreground-crop: false`は明示的な全画面送信になる。前面が画像取得前後で変わった場合も画像解析をスキップする。

背景full-screen Visionは既定で無効。背景アニメーションだけではリクエストを出さない。明示的に有効にした場合のみ最初の観測と300秒以上経過した観測で要求でき、実行中の追加要求はskipする。Evidence-first経路ではこの診断用背景解析に画像差分判定を要求しない。背景結果は同じ観測を補足するだけでprimaryを変更しない。

`mode: vision-first`で従来の画像差分・重複再利用経路へ戻せる。この場合も新しい背景既定無効・Vision上限は適用される。旧来の背景動作を再現するなら`background-full-screen-enabled: true`も必要。COMPAREモードは実装していない。

Visionモデル・endpointは従来の`rei.llm.features.activity`を使う。Activityの実リクエストでは`max_completion_tokens=1024`を指定し、従来の`max_tokens`を取り除く。**`detection.max-output-tokens`が共通/featureの出力予算に優先する。** プロンプトは前面優先・候補最大3件・短いタイトル・一文summaryを要求する。構造化schema、monitor ID検証、tool禁止、画像内の指示を実行しない制約は維持する。reasoningを含むモデルでは1024でも推論だけで上限に達する可能性は残る。サーバー側のreasoning予算を変更する機能ではない。

`/config init`で新規生成するテンプレートにもdetection設定を追加した。既存設定ファイルを自動で書き換えない。

## Provisionalと保存

Visionが必要な観測は`PROVISIONAL`、不要なら`FINAL`として先に保存する。`detection`にはEvidence、classificationSources、visionUsed、classificationMode、status、sourceConfidence、reasonを保持する。既定のmode表記は`EVIDENCE_ONLY`、成功した補足は`EVIDENCE_PLUS_VISION`。

前面Visionは元の推定より高confidenceの場合に分類を補足し、低確信度の応答で元の分類を弱めない。Visionの失敗はEvidence分類を残し`VISION_FAILED`を付ける。`visionUsed=true`はAPIを試みたことを表し、成功の保証ではない。画像取得失敗、前面変更、待機置換、pauseなどで呼ばれなかったものは`PROVISIONAL`のまま残る。この状態は「必ず後で解析される」という予約を意味しない。

enrichmentは元ID・capturedAt・durationEstimateを変えずSQLiteのRecordと当日のSession projectionを同じトランザクションで更新する。背景用Recordを追加せず、時間を二重加算しない。pause/close後に届いた応答は反映しない。

Visionが失敗しても、OS取得に成功した観測時間は残る。unknownはBehaviorの「観測成功」に含むが、娯楽・評価対象の分母には加えない。OS前面取得不能、除外、観測workerの過負荷、DB保存不能まで保証するものではなく、未取得時間を推測で埋めない。

従来JSONの`detection`欠落はnullとして読み込める。既存のraw Record/FineSession/SemanticSession/SummarySegmentの形式とBehavior evaluatorの入口を維持する。Summaryは既存projectionで再計算するため、モデルsummary全文を表示へ転記しない。

PNGの一時保存、JPEGへの変換、画像ディスクcacheは追加しない。既定では画像ファイルは作らない。Windows probeの小さなテキストJSON一時ファイルは従来どおりfinallyで削除する。構造化Evidenceを毎観測保存するため、メタデータ分のSQLite書込みは増える。

## 計測と確認

`Activity detection metrics`はプロセス内累積値として`observations`（append成功）、`evidence_only`（前面fallback不要）、`vision_fallback`（fallback必要）、`vision_skipped`、`foreground_vision`、`background_vision`、`vision_success`、`vision_failure`、`vision_timeout`、`vision_call_rate`を出す。fallback必要数と実API数は待機置換等で異なる。`evidence_only`も背景の診断設定を有効にすれば後からVision補足されることがある。call rateは(前面+背景の試行数)/保存観測数で、背景有効時は1を超え得る。再起動でリセットする。

`Activity evidence saved`の`classification_ms`はOS取得・集約・分類・初回保存までを含む。`Activity enrichment timing`の`vision_ms`は画像切り出しから補足保存まで。従来の`Activity vision timing`でinput_prepare_ms / llm_roundtrip_ms / output_parse_ms / token数を分離できる。例外ログは型・段階のみでタイトルや応答全文を出さない。

再起動後は高確信度のX/VS Code等と汎用ブラウザーを交互に観測し、metrics、保存件数、`/activity behavior evaluate`を確認する。実APIの速度・call削減率はモデルと利用画面に依存する。自動テストの待機やmockの時間を実APIのlatencyとして扱わない。

テストは `ActivityEvidenceClassifierTest`、`ActivityEvidencePipelineTest`、`ActivityEvidenceSafetyTest`、`ActivityEvidenceStorageTest`、`ActivityVisionScaleTest`。依頼に示されたタイトルを再現した`src/test/resources/activity/evidence-first-fixtures.json`、可視/非表示/最小化/画面外/除外、project+event、閾値境界、画像crop、失敗保持、実行中Visionの遅延、最新待機置換、背景opt-in、SQLite往復、時間非重複、Behavior/Summary互換、旧JSON、1024上限を検証する。fixtureは利用パターンを再現した合成データで、実データベースからの抽出ではない。実データベースや外部Vision APIをこれらのテストで直接使用しない。
