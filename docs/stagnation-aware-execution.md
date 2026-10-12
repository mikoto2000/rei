# 進捗と停滞による実行制御

## 実行単位と接続箇所

`ChatExecutionService.execute()` がユーザー依頼ごとに `RunExecutionContext` を生成し、
`ToolCallingChatOptions.toolContext` でモデルへ明示的に渡す。ThreadLocal や singleton の
停滞状態には依存しない。終了・キャンセル時には context を閉じ、後続のモデル／ツール実行を防ぐ。

`LlmChatClientProvider` と既存の `AiConfiguration` の ChatClient は `StagnationChatModel` を利用する。
context がないバックグラウンド機能の呼び出しは従来どおり委譲する。

従来は Spring AI の OpenAI モデル内部がツール反復を実行し、外側の advisor は内部反復を観測できなかった。
現在は実行対象のリクエストだけ `internalToolExecutionEnabled=false` とし、
Spring AI の `ToolCallingManager` で既存 callback（MCP・組み込み双方）を呼ぶ。
ツールの許可確認・イベント・戻り値の直接返却は既存 callback / metadata を維持する。

**LLM の判断1回と、それが要求したツール結果の受領までが1 iteration。**
複数ツールのバッチも1回と数える。ストリームの文字断片やツール数では数えない。
最終回答とモデルエラーも iteration として記録する。既存の外側の advisor は引き続き
履歴・Working Set 等の初期コンテキストを構築し、反復中のツール結果は conversation history に追加される。

## 進捗の根拠

既存の `ProgressEvent` を拡張し、不変の `ProgressEvidence(kind, description, source)` を利用する。
判定は `ProgressEvaluator` が行い、`StagnationDetector` はカウンターと状態遷移だけを管理する。

| 種別 | 根拠 |
| --- | --- |
| `NEW_INFORMATION` | 対応する読み取り・検索ツールの、実行内で未取得の結果。版付きの `readMultiFile` / `searchAndRead` は正規化パス・版・既読範囲で重複排除し、範囲の重なりやバッチの並び替えでは進捗を増やさない。成功した `runCommand` の新しい標準出力も対象。 |
| `STATE_CHANGED` | 対応するファイル変更ツールの実行前後で、内容の SHA-256 または存在状態が変化した。同じ内容の書き戻しは対象外。 |
| `SUBGOAL_COMPLETED` | 既存 ActionPlan の新しい DONE 遷移、または出力上限による分割実行で executor が確認したサブゴールの SUCCESS。同じステップ／ゴールの完了を重複加算しない。 |
| `ERROR_RESOLVED` | 同じツール・正規化JSON引数で失敗を記録した後、構造化結果の成功・終了コード0、または正常な読み取り結果を確認した。 |

対応ツールは `ProgressEvaluator` の明示的な集合で管理する。未対応ツール、時刻取得、
単なる `success`、計画の変更のみ、LLMの「進みました」という説明は、それだけで進捗としない。
MCP ツールも共通境界で実行制御するが、独自名・独自形式の進捗契約を自動推測しない。
追加する場合はツールの入出力契約に合わせた判定とテストを追加する。

この判定は観測できる事実を用いたヒューリスティックであり、結果が目的に有用かを一般的に証明するものではない。
シェルコマンド内部や未対応の外部ツールによる変更は、ファイル変更として自動検出しない。
読み取り・書き込みは対象の結果／指定パスを調べ、Working Tree 全体を毎回走査しない。
ファイルのハッシュ取得ができない場合は進捗を推定しない。

## 停滞エピソード

既定の定数は `StagnationDetector.DEFAULT_THRESHOLD=4` と `DEFAULT_MAX_REPLANS=2`。

```text
進捗なし ×4 → StagnationAdvisor の助言を次のLLM入力へ追加（再計画1）
進捗なし ×4 → 再計画2
進捗なし ×4 → STAGNATED
```

再計画時は連続停滞カウントだけを0に戻し、エピソード内の再計画回数は保持する。
意味のある進捗が得られた時点でエピソードを終了し、両カウントを0に戻す。
初めてのファイル読み取りが新情報なら進捗。その後同じ内容を4回読んだ時点で停滞となる。
再計画の助言には停滞回数、再計画回数、直近のツール名・引数ダイジェスト、エラー種別を渡す。
過去の実引数・結果はそのまま既存のツール履歴から参照できる。

## Hard limit と出力上限

`OutputLimitRunBudget` は削除・リセットしない。

- `rei.llm.max-output-tokens`：1回の出力上限（既定8192）。
- `rei.llm.output-limit.max-llm-calls-per-run`：外側の初期／サブゴール／統合呼び出し、分割プランナー、内部の追加LLM反復の合計（既定120）。
- `rei.llm.output-limit.max-replans-per-goal`：名前は既存互換のまま、出力分割と停滞再計画それぞれのRun内絶対上限（既定2）。独立カウンターを使い、両方が共通のLLM呼び出し・トークン予算を消費する。

意味のある進捗が続いてもこの上限を超えない。エピソードの回復は hard budget を回復しない。
ここで数えるLLM呼び出しはアプリケーションの論理呼び出しであり、HTTPリトライ／サーバーフォールバックや
別機能の補助LLM呼び出しは従来と同様に別扱い。

出力上限到達時、現在のモデル反復内で前回の継続以降に進捗が確認できた場合は、
その履歴を使って短い続きの生成を要求する。**この継続は再計画回数を使わず、LLM呼び出し予算を使う。**
新しい進捗がなければ既存の `OutputLimitReplanner` による意味単位の分割へ戻る。
出力上限で切れた不完全なツール引数は実行しない。

## 観測

以下の型付き Agent Event は runId を持ち、既存のイベント経路で Shell に投影される。

- `progress.detected`
- `stagnation.updated`
- `stagnation.detected`
- `stagnation.replan_requested`
- `stagnation.recovered`
- `stagnation.stopped`

payload は evidence、連続停滞回数、閾値、停滞再計画回数・上限、reason を含む。
`agent.run.failed` の error code は `stagnated`、`llm_call_budget_exceeded`、
`replan_budget_exceeded`、既存の `output_limit`／`cancelled` を区別する。

```text
[progress] STATE_CHANGED: File content/existence changed (…)
[stagnation] no progress: 3/4
[stagnation] threshold reached
[stagnation] replanning (1/2)
[stagnation] recovered: meaningful progress detected
[stagnation] repeated after replanning -> stopped (STAGNATED)
```

## 検証

`StagnationEpisodeTest`、`ProgressEvaluatorTest`、`RunExecutionContextTest`、
`StagnationChatModelTest`、`ChatExecutionStagnationTest` と既存の Shell テストで、
状態遷移・根拠の重複排除・本番 ChatClient 経由の停止・次runの独立性・予算・通知を検証する。
全体の標準コマンドは `./mvnw.cmd test` と `./mvnw.cmd verify`（JDK 25）。

## 作業完遂 Phase 2：revision と Web 証拠

`ProgressEvidence` は既存の kind / description / source に nullable `revision` を追加する。旧 constructor と旧 JSON は維持する。ファイル変更は現在の SHA-256、Change Set は APPLIED receipt の proposed/current SHA と実ファイルの一致、Dependency は対象 ID と観測状態を記録する。Change Set の再表示、古い receipt、同じファイル revision は追加進捗にならない。

ファイル fingerprint は既存 FileGoalVerifier の Project 相対パス、symlink 拒否、1 MiB・キャンセル制限を再利用する。存在・ディレクトリの変化は `absent` / `directory`、観測できない状態は `unavailable` とし、未知を変更証拠に置き換えない。汎用情報・revision・失敗の Run-local ledger は各 1024 件。情報が容量に達すると既知結果は DUPLICATE、新しい未記録結果は INFORMATION_UNKNOWN を返す。古い要素を捨てて同じ情報を新規扱いする方式は使わない。

Web は以下を分離する。

| 観測 | Planning の進捗 |
| --- | --- |
| webSearch の新しいURL・snippet | 情報取得のみを NEW_INFORMATION とする。query・日時・順位だけでは増やさない |
| webSearchAndRead / fetchUrlContent の新しい非空本文 | 本文 SHA が未観測なら NEW_INFORMATION |
| 日時・取得件数・query・assessment・aliasesだけの変化 | 増やさない |
| 別URL・別Webツールから同じ本文を取得 | 増やさない |
| 取得失敗後、本文を伴う成功 | ERROR_RESOLVED、本文が新しければ NEW_INFORMATION |
| 取得失敗後、メタデータだけの成功 | 増やさない |
| Goal の達成 | この ledger では判定せず既存 Completion Gate が独立検証 |

実際の hybrid `searchKnowledge` callback のテキストから Web 本文と indexed snippet を抽出し、質問・評価メタデータ・score は証拠から除く。Web の source は URL の SHA（`web-source:`）で識別し、生URLの認証情報を追加 payload に持たせない。revision は本文そのものの SHA。新しい本文が要求に役立つかという意味的な保証や、本文中の動的時刻の除去は行わない。意味的な妥当性・達成は Goal 条件とレビューの責務である。

成功 command の同じ stdout を、command の綴りが変わっただけで再計上しない。command 出力自体の真偽はこの軽量 ledger では検証しない。テスト・レビュー・成果物は Completion Gate の既存 saved receipt と現在 SHA の検証を維持する。Git / artifact の不明なツール応答に、成功だけを理由とする新しい進捗判定は追加していない。

局所停滞のカウントは既存どおり進捗で回復する。別管理の OutputLimitRunBudget の絶対再計画数・モデル呼び出し数・tokens は回復しない。新しい Web 情報が複数回続いても総上限は増えない。既存 Waiting、Permission、Cancellation、検索キャッシュ・取得数・出力予算には変更しない。追加の設定は不要で、既存停滞制御の有効化設定に従う。

### TDD と評価

以前の `EvidenceProgressTest` は 15 件。今回URL発見は情報取得として扱い、作業完了とは分離した。日時・hybrid query・古いreceiptの変更だけでは進捗を増やさない検証は維持する。revision 未実装の compilation Red、メタデータだけの復旧・本文SHA・command alias の実行 Red も確認した。既存の局所停滞回復を絶対上限と混同した変更は関連テストで検出して戻し、既存 assertion を維持したまま実際の Run 予算で検証している。実 callback、混合した成功/失敗 Web 結果、旧 JSON、巨大ファイル、ledger 飽和も検証する。

関連回帰（Goal 評価、Planning / Run 予算、Web / Search）は最終 227 件成功。最後の callback / 旧 JSON / 評価 / Web callback 集中検証は 16 件成功。再現は `./mvnw.cmd -B -Pfull "-Dtest=EvidenceProgressTest,GoalCompletionBaselineTest,WebBoundedToolOutputTest" test`、全体は `./mvnw.cmd -B -Pfull test`。

同じ Phase 0 fixture は検証済み完遂 70%、誤完了自己申告 30%、未達終了 30%、scripted repair 1/1、介入2、不要反復4、二重実行0、権限逸脱0、Goal予約15を維持する。この fixture は Goal gateway の double を使い Planning/Web progress ledger を通らないため、この率の改善を主張しない。実モデルの有用性・token消費の評価は未取得。個別 Red/Green は旧判定の誤進捗を再現し、修正後に同じ観測で追加進捗が出ないことを証明する。

版のない旧ファイル読取は、正規化した対象パスと行位置・内容を共有して readMultiFile / readTextFile / range 間の同じ行を再計上しない。異なる読取ツールの同一内容で実行 Red を確認し、新しい行だけを進捗にする Green を得た。

## 大量文書レビューと既読範囲

`InformationLedger` は版付きファイル情報と汎用結果のダイジェストを分ける。ファイルは正規化絶対パスと読み取りツールが返す `version`（既存 FileSnapshots のファイル全体 SHA-256）ごとに、1始まり・終端を含まない範囲を保持する。重複・隣接範囲は統合する。1–400行の後に301–700行を読むと新規300行、保存範囲は `[1,701)`。1万行を25回で読んでも1範囲であり、120ファイルを処理しても120項目になる。旧版も有界容量内で保持し、版を戻したときも再計上しない。評価器は版取得のための追加ディスク読み取りを行わない。

空結果、失敗、範囲外指定は既読範囲を増やさない。要求したendLineではなく返されたcontentの行数を使う。byte上限で切れた最終行、offset付き再開の先頭行は完全既読範囲に含めず、観測した断片をダイジェストで重複排除する。symbol表示は省略を含み得るため連続範囲と推定しない。版のない旧ツール結果は従来の内容識別を使う。版を保証できない結果をmtimeだけで新しい版と断定しない。既存ツールの1MiBファイル上限は維持する。

ファイル版は最大1024、統合済み範囲も合計最大1024、パスと版の文字数合計は32768以内。汎用情報は最大1024ダイジェスト。満杯になっても既知の範囲・ダイジェストは重複と判定し、未記録情報はUNKNOWNにする。LRU削除は行わない。UNKNOWNはGoal完了・新規情報・通常の重複のいずれでもない。UNKNOWNだけの反復は通常の停滞カウントに加えない。16反復ごとに診断の再計画を最大2回促す。これは通常の停滞・出力分割カウンターを消費せず、後続モデル呼び出しは共通LLM/token上限を消費する。UNKNOWNが続いても共通Run予算で有限に停止する。

情報取得と作業成果は別に扱う。NEW_INFORMATIONはActionPlanをDONEにしない。STATE_CHANGEDまたはSUBGOAL_COMPLETEDがなく情報取得だけが32反復続くとWORK_STALLEDとし、連続停滞閾値を通じて再計画する。読み取り・検索の成功だけでレビュー完了数を増やさない。文書120件のレビューではActionPlanに対象やバッチを登録し、「確認」「レビュー」「結果記録」を別工程として管理し、実際の工程完了でDONEへ進める。Goal完了は既存Completion Gateが独立検証する。これらの猶予は内部定数で、意味的なレビュー成果の自動判定ではない。

Checkpointの既存ツール結果バッチ保存にnullable `progress` を併記し、範囲・結果ダイジェスト・予算・情報取得のみの反復数・判定不能の診断回数を保持する。本文は保存しない。SQLiteの既存JSONスナップショットに追加するためテーブル変更は不要。既存constructorを維持し、旧JSONはprogress=nullで読み込める。旧Checkpointからの再開は過去の情報が不明なためUNKNOWNとして扱い、未読と断定して進捗を水増ししない。Checkpoint保存失敗は既存の原子的トランザクションで前の版を残す。保存前の観測や反復末尾のカウンターはクラッシュ時に失われ得るため、直前バッチ以前の保存状態から再開する。別Runの新規実行には復元しない。Checkpoint無効時は永続的な既読状態復元を提供しない。

診断イベントは既存ExecutionProgressPayloadを維持する。FILE_RANGE証拠のdescriptionに `type=FILE_RANGE newLines=300`、revisionに版を記録する。停滞reasonは `REPEATED_READ`、`REPEATED_SEARCH_OR_ACTION`、`TOOL_FAILURE`、`WORK_STALLED`、`INFORMATION_UNKNOWN` を区別する。本文・認証情報・巨大結果は証拠へ追加しない。ShellはUNKNOWNを「progress unknown」と表示し、既存カウンター表示を保った診断行でreasonとledgerStatusも表示する。汎用結果は結果自体の真偽や有用性を保証せず、独立完了検証を引き続き必要とする。
