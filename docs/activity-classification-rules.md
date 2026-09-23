# Activity Classification Toolkit — Phase 3.7.1

分類カテゴリと娯楽判定を別々に管理する運用基盤。ルール候補の生成は明示コマンドだけで実行し、設定ファイルへの追記・有効化・既存ルールの変更を自動では行わない。

## 使い方

```text
/activity classification status
/activity classification unknowns --top 20
/activity classification rules
/activity classification suggest-rules
/activity classification reload
/activity behavior uncertain --top 20
/activity behavior suggest-rules
```

いずれもサブコマンド補完に対応する。`unknowns`は過去に未分類・低確信度だった候補を出現回数順に表示し、`open`と解決状態で現在の未解決観測を区別する。Visionで解決済みの候補も、Evidence用ルールを追加する材料として表示する。
`--top`は1〜100に制限、既定20。`--since`とrule詳細コマンドは未実装。

分類改善はunknowns→suggest-rules→人間による確認・ファイル編集→reload→status/rulesによる確認の順で行う。
娯楽判定はbehavior uncertain→behavior suggest-rules→同じファイルのentertainmentRulesへ手動追加→reload→次の観測・behavior evaluateで確認する。
既存の通知on/off、手動evaluateの通知しない動作は変えない。

## 設定

```yaml
rei:
  activity:
    classification:
      user-rules-file: activity/classification-rules.yaml
      hot-reload: true
      unknown-registry:
        enabled: true
        retention-days: 30
        max-entries: 1000
      entertainment-registry:
        enabled: true
        retention-days: 30
        max-entries: 1000
      rule-suggestion:
        enabled: true
        minimum-samples: 3
      diagnostics:
        enabled: true
```

すべて既定値。相対パスはRei Data Dir基準。通常のWindows環境では`%LOCALAPPDATA%/Rei/activity/classification-rules.yaml`になる。
ファイルが存在しなければbuilt-inのみで動作し、ユーザー用ファイルを自動生成しない。`/config init`の設定テンプレートには上記項目を含む。
`diagnostics.enabled`は詳細DEBUGログを制御する。Behaviorが利用する構造化disposition自体は無効にしない。

完全な例は[classification-rules.example.yaml](classification-rules.example.yaml)を参照。自分の用途を確認してから必要なルールをコピーする。

## ルールと優先順位

`OperationalRules`がbuilt-inとuserをimmutableなSnapshotへまとめ、`AtomicReference`で有効な世代を公開する。
Activity用built-inはPhase 3.7の`BrowserTitleRules`/`WindowActivityRules`を維持する。単純な正規表現に置き換えて、GitHubを常に開発扱いにするような変更はしない。
X/Twitter、YouTube/Music、Amazon、GitHub、Bluesky、Google News、ChatGPT/OpenAI、検索、repository候補、VS Code、native ChatGPT、汎用browser/terminal/processの計16定義。
動的なproject/content抽出は既存コードに残り、rule一覧は同じregistry由来のID/priorityを表示する。

Entertainment用built-inは`activity/operational-rules.yaml`に5定義:

| ID | 条件 | 判定 |
|---|---|---|
| youtube-tech | YouTube系serviceかつ技術・tutorial等のタイトル | NON_ENTERTAINMENT |
| youtube-music | YouTube系serviceかつMV/Music Video/Official Audio/配信等 | ENTERTAINMENT |
| social-default | category=social | ENTERTAINMENT |
| shopping-gaming-default | category=shopping/gaming | ENTERTAINMENT |
| work-default | development/research/documentation/monitoring/navigation | NON_ENTERTAINMENT |

YouTube/ChatGPT/Discord/Slack/Browser/GitHubを**serviceだけで娯楽に固定するbuilt-inはない**。
用途が分からないmediaなどはUNCERTAIN。ユーザーがserviceだけの明示ルールを書くことは可能だが、LLM提案では文脈条件も必須にする。

優先順位はpriority降順→指定条件の個数降順→ID辞書順。通常のbuilt-in分類は100、検索homeは90、repository候補は50。
user-specificな上書きは200〜300程度が目安。built-inを直接変更せず、別IDの高priorityルールを使う。
同じIDは同一ファイル内・classification/entertainment間・built-in/user間のいずれも拒否する。
同priority・同条件数のActivityルールが異なるcategoryを返す場合、IDで勝者を決めるがcategory confidenceを0にし、CONFLICTING_RULESとしてVision候補にする。
Entertainmentの同順位でdispositionが衝突すればUNCERTAIN。単に低順位のgenericルールが一致することは衝突扱いしない。

## スキーマ

ルートは`classificationRules`と`entertainmentRules`のみ。両方ともリスト。

各ルールは`id`、`enabled`（既定true）、`priority`（既定100）、`match`、`classify`。
IDは英数字・ハイフン・アンダースコアの1〜80文字、priorityは0〜10000の整数。

Activityのmatchは`processRegex`と`titleRegex`。Entertainmentはさらに`serviceRegex`、`applicationRegex`、`contentRegex`、`activityCategoryRegex`。
指定した条件はすべてANDで、正規表現は`find()`で部分一致。完全一致が必要なら`^...$`を使う。
match対象は最大512文字。正規表現はreload時だけコンパイルする。

Activityのclassify:

```yaml
category: research                  # 必須、既存13カテゴリ
service: Example                    # 以下は省略可
application: Firefox
projectCandidate: rei
contentCandidate: API reference
categoryConfidence: 0.9
serviceConfidence: 0.95
applicationConfidence: 1.0
projectConfidence: 0.8
contentConfidence: 0.8
```

未指定フィールドは既存分類から保持する。欠落フィールドの確信度は0。categoryの既定confidenceは0.8、新規service/applicationは0.9、新規project/contentは0.8。
category=unknownのcategoryConfidenceは0のみ。別形式としてルール直下に`confidence: {category: 0.9, service: 0.95, ...}`も使用できるが、同じ軸を両形式で指定すると拒否する。
usable/partial/completeとVision skip基準はPhase 3.7と同じ。

Entertainmentのclassifyは`entertainmentDisposition`と`confidence`だけ。dispositionはENTERTAINMENT/NON_ENTERTAINMENT/UNCERTAIN、confidenceは必須の0〜1。
confidenceが0.8未満ならUNCERTAINへ落とす。Activityのcategoryを書き換えない。

未知キー、不正な型、空ID、重複ID、空match、不正category/disposition、範囲外confidence/priority、正規表現エラー、YAML重複キー/aliasを拒否する。
ファイルは256KiB、各配列500件まで。regexは256文字までとし、後方参照・繰り返すgroup・多数の非限定量指定子を拒否する保守的な部分集合。高度な正規表現DSLとしての利用は想定しない。

## reload

3秒間隔の専用scheduled pollで、小さい設定ファイルの内容hashを確認する。Captureの各観測ではファイル読込・YAML parse・rule regex compileを行わない。
ファイル作成・変更・削除を検出し、load→parse→validate→compile→effective set作成がすべて成功した場合だけatomic swapする。
失敗時は前の世代を維持し、通常ログに秘密情報や不正なYAML全文を出さない。起動時に壊れていてもbuilt-inで動作する。
`/activity classification reload`でも同じ処理を明示実行できる。ファイルを削除してreloadすればbuilt-inのみへ戻る。
last reload time/statusはstatusに表示する。

## Registryと診断

`ClassificationToolkit`がActivityStoreを包み、本体の保存後に`ClassificationTelemetryRepository`へmetadataを保存する。
SQLiteの専用`activity_classification_telemetry`と`activity_classification_candidates`テーブルを使用し、ActivityRecord/sessionとは責務を分離する。
Registryのcandidatesは種類+normalized keyごとに1行を保存し、count等を更新する。計測履歴はObservation IDでupsertし、同じ観測のVision補足を追加観測として数えない。
影響を受けたkeyの集計を、計測履歴と同じトランザクションで更新する。候補ごとの索引を利用し、通常の更新で全候補を再集計しない。

保存項目はprocess/title/application/service/content/category、時刻、各軸confidence、matched/winning rule、source、usable/vision判断、unknown reasons、娯楽disposition/confidence/rule、Vision試行/成功/失敗理由。
画像・base64・OCR全文は保存しない。初回のfallback判断と歴史的unknown理由はVision補足後も保持する。

Unknown keyは小文字化・連続空白正規化したprocess+title。Unknown registryはcategory unknownだけでなく、低確信度などusableでない分類も含む。
Entertainment keyはprocess+title+service+content。UNCERTAINになった観測を保持する。
count/firstSeen/lastSeen/open/状態、Vision試行数/成功数、結果category/serviceの頻度も集計する。
同じObservationがVisionでusableになればopenが減り、全件解決ならRESOLVED_BY_VISIONになる。
新規ルールは新しい観測から適用され、古いOPEN観測を自動で再分類しない。再処理コマンドも未実装。

各registryは既定30日・1000集約keyまで。古いkeyから対象外にし、期間外観測を削除する。読込時も期間を適用する。
補足履歴と当日metricsのため、対象外keyのmetadata行は全体のretentionまで残るが、総行数は追加で100000行に制限する。
従って長期間・大量観測時の件数は、永続的な総数ではなく現在保持している期間の値。

Unknown reasons:
`NO_RULE_MATCH`, `LOW_CONFIDENCE`, `CONFLICTING_RULES`, `GENERIC_WINDOW_TITLE`, `GENERIC_BROWSER_TITLE`, `INSUFFICIENT_EVIDENCE`, `VISION_FAILED`, `VISION_OUTPUT_LIMIT`, `VISION_TIMEOUT`, `VISION_VALIDATION_FAILED`。
複数理由を保持できる。

Entertainment reasons:
`NO_ENTERTAINMENT_RULE_MATCH`, `LOW_ENTERTAINMENT_CONFIDENCE`, `CONFLICTING_ENTERTAINMENT_RULES`, `INSUFFICIENT_CONTENT_CONTEXT`, `MATCHED`。

`ActivityRecord.Detection.diagnostics`には構造化診断を追加する。旧JSONでフィールドが欠けていてもnullとして読める。
DEBUGにはrule/source/usable/vision reason/dispositionを出す。通常INFOに各観測のタイトル全文を出さない。

## Metrics

statusは設定zoneの当日・保持済みtelemetryからobservations、初回evidence-only/fallbackと率、最新unknown/partial、分類rule適用/未適用、built-in/user適用、output_limit/timeout、娯楽/非娯楽/uncertain、娯楽rule/user適用を表示する。
`rules`のhitは当日、そのルールが勝者として適用された観測数。enrichmentで同じIDを二重計上しない。未使用ルールも0で表示する。
提案生成件数だけはプロセス内累積で再起動時に0へ戻る。
`vision_observations`はAPIを試みたことが保存された観測数で、前面/背景の個別API call数ではない。進行中の呼出しは完了・失敗の保存後に反映する。
実API call率は既存の`Activity detection metrics`のforeground_vision/background_vision/vision_call_rateで確認する。
Telemetry失敗時は本体観測を維持しWARNを出す。その場合statusは欠落分を含まないため、成功ログ・本体件数との比較が必要。

## LLM提案

`ClassificationRuleSuggestions`が頻出候補を選び、`LlmClassificationRuleModel`を使って1コマンドにつき最大1候補を提案する。
モデル/endpointは`rei.llm.features.activity`を利用し、toolなし・出力2048上限・JSON schema指定。自動定期生成やretryは追加しない。
最低3サンプル（設定可能）。Activity候補は成功Visionが最低数あり、category/serviceの結果が一種類で一貫することを要求する。
Entertainment候補もcategory/serviceの用途が混在していれば除外する。既存ルールで対応済みなら呼び出さない。

入力は除外・機密チェック後の上記候補metadataと既存ルール情報（最大100件、全入力64Ki文字以内）。画像、会話履歴、認証情報を送らない。
タイトル・ルール文字列は命令ではなく信頼しないデータとして扱う。
出力schemaは`activity/rule-suggestion.schema.json`。ruleType/id/priority/match/classify/proposalConfidence/rationaleの単一object、余分なキーを拒否する。
未使用match/classifyフィールドはnull。通常ルール用YAMLへ変換後、通常のcompile/validationを再利用する。

追加検証はproposalConfidence>=0.7、既存ID/同一match重複の拒否、対象sampleに実際に一致すること、Activity結果が成功Visionと一致すること、機密文字列の抑止。
scope（process/service/application）とcontext（title/content）の両方を要求する。空文字や無関係な検査文字列にも一致するregexなどは過剰に広い候補として拒否する。
これは全正規表現についての数学的な意味同値判定ではない。人間による最終レビューを省略しない。
proposalConfidenceはモデルの提案確信度で、校正済み確率ではない。混在用途は事前除外するが、将来の全用途を保証するものではない。

候補はコピー可能なYAMLと短い根拠として表示する。suggestionコードにはルール書込・reloadを呼ぶ経路がなく、前後のeffective snapshotが同一であることをテストする。
LLM不可・不正JSON・不正regex・打ち切り等でも現在の有効ルールを変更せず、registry/status/reloadは引き続き利用できる。

## Behavior互換性とPrivacy

新しい観測ではルールから決まったdispositionを保存し、BehaviorEvaluatorがcategory fallbackより優先する。
media+NON_ENTERTAINMENT、research+ENTERTAINMENTを許容し、UNCERTAINは娯楽時間に加算しない。
診断のない旧Recordのみ、従来のentertainment-categories設定へfallbackする。役割・確信度・観測時間・window計算とSummaryのカテゴリは維持する。
過去のRecordの娯楽判定をreload時に書き換えないため、新旧観測が同じ60分窓に混在することはある。

CapturePolicyのprocess/title除外をRegistry/Diagnosticsでも再確認する。機密検出（メール/電話/secret等）に該当する候補は新規保存・提案対象にしない。title/content等は384文字に制限し制御文字を除く。
Privacy除外された観測はtoolkit metricsにも含まれない。本体のPhase 3.6/3.7の先行保存、RAM画像、既定画像保存なし、背景opt-in、前面1件+最新待機1件は維持する。
