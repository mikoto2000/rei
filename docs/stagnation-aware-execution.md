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
| `NEW_INFORMATION` | 対応する読み取り・検索ツールの、実行内で未取得の結果。`readMultiFile` はパス・行番号・行内容で重複排除し、範囲の重なりやバッチの並び替えでは進捗を増やさない。成功した `runCommand` の新しい標準出力も対象。 |
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
- `rei.llm.output-limit.max-llm-calls-per-run`：外側の初期／サブゴール／統合呼び出し、分割プランナー、内部の追加LLM反復の合計（既定30）。
- `rei.llm.output-limit.max-replans-per-goal`：名前は既存互換のまま、実装上はrun全体の再計画上限（既定2）。出力上限による分割と停滞再計画で共有する。

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
