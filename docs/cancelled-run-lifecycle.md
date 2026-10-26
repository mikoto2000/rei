# `/cancel` と会話のライフサイクル

## 原因

チャットの購読解除と実行スレッドへの interrupt は実装されていたが、会話上の依頼の状態と結び付いていなかった。

- `ChatExecutionService` は `finally` で未適用 guidance を無条件に履歴へ追加していた。
- チャット履歴にはユーザー依頼が残る一方、中止されたことを示す状態はなかった。
- `AgentSkillImplicitSelector` と `FallbackChatModel` は、キャンセルやラップされた interruption を通常の失敗として処理していた。
- スキル選択が割り込みを無視して戻った場合、キャンセル後にも選択完了を通知できた。
- 話題候補の更新にも interruption を握り潰す経路があり、run の完了通知が更新処理より先に出ていた。
- Shell、UI projection、プロジェクトの最終 run 状態はキャンセルを `FAILED` と表示していた。

元のログだけでは実際に送信されたプロンプトや pending guidance の内容は断定できない。上記の経路はコードと回帰テストで確認した。

## 実行の終了と伝播

`/cancel` → `CommandCancellationService` → run 所有の cancellation hook →
`RunExecutionContext.cancel()` → mailbox の破棄・終了、実行コンテキストの閉鎖。
既存の購読解除と実行スレッドへの interrupt はそのまま利用する。

`RunCancellation` は `CancellationException`、Reactor cancellation、cause に含まれる
`InterruptedException` を中断として認識する。下位の同期処理では interrupt 状態を復元し、
キャンセル例外として伝播する。run の待機処理は、自身が要求した interrupt を終端結果に変換する。
終了時のメタデータ保存では interrupt を一時的に解除し、cleanup 後に復元する。

スキル選択、advisor、再計画、チャット・ツールのループは run の有効性を確認する。
割り込みを無視する外部処理が戻ってきても、後続の通常処理を開始させない。
既に発生したツールの外部副作用を巻き戻す仕組みではない。

通常の LLM 障害に対するスキル未選択やフォールバックは維持する。キャンセルではそれらを使わない。
話題候補更新を含む後処理が終わってから run の完了を通知する。

## Guidance の所有権

`UserInterventionQueue.discardAndFinish()` は pending 指示の破棄と受付終了を同じロックで行う。
キャンセルされた run の指示はチャット履歴へ追加せず、次の run にも適用しない。
破棄件数は run ID とともに core のログへ記録する。

適用とキャンセルは実行コンテキスト上で同期する。キャンセル前に既に適用済みだった指示は過去の会話として残る。
正常 run の guidance の適用・継続、および通常失敗時の既存の履歴保存動作は維持する。
mailbox が閉じた後の新規入力は、既存 router により後継 run として扱われる。

## 会話状態とプロンプト

`ConversationTurnStore` は `runId`、元の依頼、`RUNNING / COMPLETED / FAILED / CANCELLED` を保持する。
終端状態は後の cleanup で上書きしない。

本番の `SqliteConversationTurnStore` は Rei データディレクトリの `storage.db` に保存する。旧形式の `ConversationTurnStore` は `state/turns/<conversation-key>.json`、プロジェクト付き会話では `projects/<projectId>/state/turns/` を使う。どちらも再起動後にキャンセル状態を読み戻し、会話 ID ごとに分離する。通常の会話ログとメモリは削除しない。ライフサイクル情報は会話ログ本文とは別のメタデータであり、既存 JSONL の形式は変更しない。

`ContextHistoryAdvisor` はモデルへ渡す履歴を投影するときに、キャンセル済みの依頼・応答・途中の guidance・run 要約を除外する。実行を中止した時点で直前に連続している `FAILED` の依頼も、自動継続された可能性のある未完了の試行として除外する。`COMPLETED` または別の実行中の依頼を越えて遡らない。タスク同一性を推測して削除する処理ではなく、保守的な自動注入の制限であり、別件の連続した失敗も参照が必要なら明示的に取得する。

永続ログは実行の開始日時により turn と対応付ける。turn から組み立てる旧形式の履歴にも同じ除外を適用する。元のログ、turn、run 要約は変更しない。過去の圧縮要約から依頼が復活しないよう、最新のキャンセル run ID ごとに履歴要約の保存キーを分離する。キャンセル後の新しい会話は通常どおり保持・圧縮する。

`ConversationLifecycleAdvisor` はキャンセルした依頼の本文を再注入しない。一般的な停止方針と履歴取得先の conversation ID のみを渡す。

> 現在のユーザー入力に応答し、キャンセル済みの作業を自動で取得・再開しない。
> 明示的な再開や振り返りを頼まれた場合は、`getConversationHistory` / `searchConversationHistory`、必要に応じて `workContextGet` で記録を取得する。
> キャンセルは変更済みファイルや起動済みプロセスを巻き戻さないため、操作の重複を避けるため実際の状態を確認する。

挨拶や再開要求を文字列で判定しない。明示的な再開でも古い依頼を自動注入へ戻すのではなく、モデルが必要な履歴を取得して再開する。会話ごとの除外は再起動後や複数の無関係な会話を挟んだ後も保持する。

## Working Set と状態表示

Working Set はファイルの参照情報であり、別の `TaskState` と `ActionPlan` が作業目的や手順を保持する。これらの保存内容は削除しない。Task State、Action Plan、旧形式の再開 checkpoint は作成元 run を記録し、キャンセル以前の状態や所有元が不明な状態は後続のプロンプトへ注入しない。新しい run が設定した作業状態は利用できる。古い Action Plan を進捗判定に使用したり、旧 checkpoint の再開ヒントを新しい永続 checkpoint の次操作へ引き継いだりしない。

Work Context も投影だけを変更する。古い目的・現在作業・未完了項目・阻害要因・次操作は、キャンセル後の新しい run の根拠があるものだけを注入する。成果物、実施済み作業、検証、判断の記録は参照できる。`/work show` や取得ツールが返す保存記録は変更しない。Working Set と変更済みファイル、外部プロセスは維持する。

既存の run 終端イベントと `error.code = cancelled` を利用する。イベント API は追加せず、
Shell は `[agent] cancelled`、UI projection と最終 run の保存状態は `CANCELLED` とする。
通常エラーは引き続き `FAILED` として扱う。

## 回帰テスト

| 対象 | 確認内容 |
| --- | --- |
| `ChatExecutionInterventionTest` | キャンセル結果、pending guidance 破棄、複数の挨拶と明示的再開要求でも元の依頼を自動注入しないこと、履歴取得の案内、Working Set 保持、正常 guidance、終端状態の一貫性 |
| `CancelledTaskProjectionTest` | ファイル/SQLite の再起動、失敗した元依頼からの誤再開、guidance・run 要約・古い圧縮要約の除外、原本保持、別会話への非干渉 |
| `CancelledActiveStateTest` / `CancelledWorkContextTest` | 古い作業状態の非注入と原本保持、新規タスクの注入、実施済み変更と成果物の保持 |
| `CancelledRunBoundaryTest` | 割り込みを無視するスキル選択、通信の購読解除、ツール中断後の後続抑止、話題更新中断時の完了抑止 |
| `ConversationTurnStoreTest` | 永続化と再起動、会話分離、通常完了後も過去のキャンセルを保持、終端状態の保護 |
| `AgentSkillImplicitSelectorTest` | ラップされた interruption の伝播と interrupt 復元 |
| `FallbackChatModelTest` | 同期・非同期キャンセルで fallback を呼ばない、キャンセル後の通常通信エラーでも fallback を開始しない |
| `ChatExecutionStagnationTest` | 出力上限とキャンセルが重なっても再計画を開始しない |
| `TopicCancellationTest` | 中断された話題候補生成から次の生成処理へ進まない |
| `ChatCommandCancellationTest` | Shell のキャンセル表示とストリーム停止 |
| `ProjectRunStateStoreTest` / `DefaultAgentUiProjectionTest` | 保存状態と UI が cancellation と failure を区別する |

テストは latch と模擬モデルで実行境界とプロンプトを検証する。実際の LLM の自然言語応答を保証するテストではない。
特定のモデルに対する応答品質の確認は、別途ライブ環境で行う。

Red → Green → Refactor の各段階の実行ログは `target/cancel-*.log` に保存する。
全体テストは `./mvnw test` で実行できる。既存のベクトル検索テストは GitHub から sqlite-vec を取得するため、ネットワーク接続を必要とする。

## 検証結果（2026-09-12）

- ブランチ: `fix/cancelled-run-state`
- JDK 25、Maven Wrapper 3.9.14、Windows で検証。
- 新規テスト 15 件と、既存 Shell cancellation テストの期待表示変更。
- 最終結果: **1,511 tests、Failures 0、Errors 0、Skipped 0、BUILD SUCCESS**。
- 最終ログ: `target/cancel-full-final.log`。`git diff --cached --check` も成功。
- 最初の全体実行では、ネットワーク制限により既存 sqlite-vec テスト 27 件がエラーとなった。
  必要なダウンロードを許可して全体を再実行し、全件成功を確認した。
- `component.puml` の作業中の変更は、ユーザーの指示により今回のコミットに含めない。

## 変更ファイル一覧

本番コード（以下は `src/main/java/dev/mikoto2000/rei/` からの相対パス）:

```text
conversation/ConversationTurnStore.java
core/chat/ChatExecutionResult.java
core/chat/ChatExecutionService.java
core/chat/ConversationLifecycleAdvisor.java
core/chat/RunCancellation.java
core/chat/RunScopedAdvisor.java
core/chat/UserInterventionQueue.java
core/project/ProjectRunStateStore.java
core/stagnation/RunExecutionContext.java
llm/FallbackChatModel.java
skills/AgentSkillAdvisor.java
skills/AgentSkillImplicitSelector.java
topic/TopicGeneratorService.java
ui/projection/AgentRunStatus.java
ui/projection/DefaultAgentUiProjection.java
ui/shell/ShellAgentEventRenderer.java
```

テスト（以下は `src/test/java/dev/mikoto2000/rei/` からの相対パス）:

```text
conversation/ConversationTurnStoreTest.java
core/chat/CancelledRunBoundaryTest.java
core/chat/ChatExecutionInterventionTest.java
core/chat/ChatExecutionStagnationTest.java
core/command/ChatCommandCancellationTest.java
core/project/ProjectRunStateStoreTest.java
llm/FallbackChatModelTest.java
skills/AgentSkillImplicitSelectorTest.java
topic/TopicCancellationTest.java
ui/projection/DefaultAgentUiProjectionTest.java
```

設計文書: `docs/cancelled-run-lifecycle.md`。合計 27 ファイル。

## 自動注入からの除外の検証（2026-10-26）

- 通常テスト: 3,823 件、Failures 0、Errors 0、Skipped 3。
- 関連回帰テスト: 201 件、Failures 0、Errors 0、Skipped 0。ファイル保存と SQLite の両方を検証。
- `PackageArchitectureTest` で循環依存が増えていないことを確認。既存 baseline は変更していない。
- Mockito の javaagent、ワークスペース内の一時領域、子 JVM の UTF-8 出力を指定。既存の標準出力検証へ診断ログが混入するため、テスト時のみ `CommandCancellationService` の INFO ログを抑止した。
- `build.bat -o` で Backend と軽量 CLI の JAR を更新。Backend JAR 内の新しい文脈判定クラスと停止方針を確認。
- 実行ログは `target/cancelled-task-unit-tests-final.log`、`target/cancelled-task-integration-final.log`、`target/cancelled-task-build.log`。

実モデルの自然言語応答ではなく、送信する文脈・保存記録・状態の扱いを検証している。
