# コンテキスト自動圧縮 Phase 1–3 実装報告

詳細な設計・設定・処理フローは [context-compression.md](context-compression.md) を参照。

## 調査と採用設計

開始時は `main`、working tree は clean。基点は `628f82ce53fc0086d7b32c1972b53ee04c8e6866`。
`codex/context-auto-compression` を作成して作業した。

100件制限は bounded ChatMemory の制限で、永続会話ログとは別だった。
完全な履歴は JSONL の ConversationLogStore、turn lifecycle は ConversationTurnStore、Tool Event は短い結果要約を保存していた。
途中介入や URL 要約は turn 一覧に含まれないため、Projection は完全な会話ログを優先し、turn / bounded memory を互換経路として扱う。
Agent loop は StagnationChatModel が所有するため、ここに最終 request の組み立てを接続した。

## Phase 1

- token 推定、出力・Tool schema 予約、安全余裕、モデル別 window、圧縮閾値・hard limit を設定可能にした。
- 古い prefix のみを previous summary に統合し、recent messages は原文で残す。
- throughSequence と updatedAt を持つ Summary を project state に atomic 保存する。
- Conversation Log / Turn / bounded ChatMemory は削除しない。
- ログには新規追記分から安定した sequence を加えた。旧ファイルは書き換えず読み取れる。
- 要約失敗・空出力・出力上限・削減率不足時は旧 Summary を保持し、Projection の recent window を縮めて再判定する。
- 収まらない場合は `CONTEXT_HARD_LIMIT` を Chat 実行結果まで伝える。

## Phase 2

- 各 LLM request 境界で ContextAssembler を呼び、Tool 実行途中には Projection を変更しない。
- Working Set は独立した section に移し、各境界で現在の状態を再取得する。現在のユーザー入力、追加指示、Task State 等も要約対象外。
- 会話 Summary と Run observations は別 cursor。サブゴール request でも run sequence を再利用しない。
- started / completed / failed の圧縮イベントを既存 Event Factory、audit、Web DTO、SSE に接続した。
- Native / Java Projection は未知イベントを安全に無視して cursor を更新する既存動作を維持した。
- 要約の HTTP stream は親 subscription のキャンセルに従い、保存前にも Run の monitor 内で cancellation を検証する。

## Phase 3

- Tool 完了時に原文を project state へ保存する。returnDirect の結果も保存対象。
- 小さい結果は変更せず、大きい結果のみ deterministic に command / status / exitCode / failure 等と head・tail・rawResultRef へ短縮する。
- `readRawToolResult` による conversation 単位のページ再取得を追加した。
- Model にだけ compact representation を渡し、loop / Tool dispatcher の raw prompt は維持する。

## 主なクラス

| 責務 | クラス |
|---|---|
| 最終 Projection | `ContextAssembler`, `ContextHistoryAdvisor` |
| 計測・切り分け | `ContextBudgetManager`, `TokenEstimator`, `CompressionPolicy`, `ContextCompressionProperties` |
| 要約生成・永続化 | `ConversationCompressor`, `LlmConversationCompressor`, `ConversationSummary`, `ConversationSummaryRepository` |
| Tool result | `ToolResultCompressor`, `RawToolResultStore`, `RawToolResultTools` |
| loop・キャンセル | `StagnationChatModel`, `RunExecutionContext`, `ChatExecutionService` |
| 履歴・Working Set | `ConversationLogStore`, `ConversationLogEntry`, `WorkingSetAdvisor`, `ConversationLifecycleAdvisor` |
| 配線・イベント | `ContextCompressionConfiguration`, `AiConfiguration`, `LlmChatClientProvider`, `ContextCompressionPayload`, `AgentEventFactory`, `WebApiEventMapper` |

## 発動・保存・並行性

既定 threshold は70000、hard limit は85000。実効上限はモデル window から出力予約・Tool schema 予約・安全余裕を引いた値との小さい方。
recent 予算は各12000、Summary は各3000、最低削減率10%。設定方法・実行フロー・保存先は設計文書に記載した。
同じ conversation の read / summarize / commit は interruptible lock で直列化する。新規範囲がなければ再要約せず、各境界につき各 scope 最大1回。
要約も Run の LLM call budget に算入する。失敗による無制限 retry はしない。

## TDD・テスト

Red → Green の記録は estimator/policy、assembler、loop 接続、Web DTO、安定した log sequence、hard limit の実行結果に分けて確認した。
リファクタ時には各 focused test と全スイートを実行した。

新規テスト:

- `CompressionPolicyTest`: token 推定、Tool pair の境界、section 優先順位。
- `ContextAssemblerTest`: 閾値未到達、旧履歴のみの要約、rolling、再起動、縮退、削減率不足、キャンセル、並行性、Working Set、hard limit。
- `ContextBoundaryTest`: Tool 完了→組み立て→次の LLM の順序と raw prompt の保持。
- `ContextCancellationTest`: 親の dispose が要約の stream を実際にキャンセルすること。
- `ContextChatIntegrationTest`: 実際の ChatClient / advisor / model chain と圧縮イベントの owner。
- `ContextHistoryAdvisorTest`: bounded memory を超える永続履歴、途中介入・補助コマンド、Working Set の独立領域。
- `ToolResultCompressorTest`: 小さい結果の維持、診断情報、原文の再取得、conversation 分離。
- `CompressionEventTest`: Web DTO の数値情報と hard limit の安全な公開。
- `ContextLimitExecutionTest`: Chat 実行結果の明示的な hard-limit error。
- `ConversationLogStoreTest` の追加ケース: 再起動・時計巻き戻り・旧 JSONL の無変更読み取り。

最初の制限環境での全スイートは、変更前の基点も変更後も同じ34件のエラーだった。
内訳は sqlite-vec テスト用ダウンロード制限と既定ログ保存先への書き込み制限。
`REI_DATA_DIR` を workspace の test data に設定し、ダウンロード可能な実行環境で解消した。
稼働中 JAR のロックで `mvn clean` は失敗したため、JAR を保持し、classes / test-classes / surefire-reports / maven-status だけを削除して再生成した。

最終結果:

| スイート | コマンド | 結果 |
|---|---|---|
| Java 全体 | `mvnw -o -Dmaven.repo.local=F:\project\rei\.m2\repository test` | 1841件成功、失敗0、エラー0、skip 0 |
| Client | `npm test -- --reporter=dot` | 39件成功 |
| Native Client | `cargo test --offline --manifest-path client/src-tauri/Cargo.toml` | 64件成功 |
| 差分形式 | `git diff --cached --check` | 問題なし |

Java は JDK 25 と `REI_DATA_DIR=F:\project\rei\target\context-test-data` を使用した。
Java の新規ケースは基点の1817件に対して24件。旧コードで再現した34件の環境エラーは解消済みで、最終結果には残っていない。

## 残課題

- tokenizer と media の token 計測は概算。実モデルの window を設定する必要がある。
- 大規模な会話ログ読み取りの索引化と raw result 保存容量・保管期限の設計。
- 圧縮状況を専用表示する UI は未追加。イベントストリームからは観測可能。
- live LLM の要約品質は自動テストの対象外。テストは fake / mock stream により境界と制御を検証する。

コミットハッシュと最終 `git status` は完了時の報告に記載する。
