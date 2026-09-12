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

本番では既存の Rei データディレクトリの `state/turns/<conversation-key>.json` に保存する。
プロジェクト付き会話は `projects/<projectId>/state/turns/` を使う。ファイルは一時ファイルから置換し、
プロセス再起動後もキャンセルを保持する。会話 ID ごとに分離し、通常の会話ログとメモリは削除しない。
ライフサイクル情報は会話ログ本文とは別のメタデータであり、既存 JSONL の形式は変更しない。

`ConversationLifecycleAdvisor` が履歴・作業コンテキスト・スキルの組み立て後に、
キャンセル済みの依頼と次の方針を system コンテキストへ反映する。

> 明示的な再開指示がない限り、キャンセル済みの依頼を再開しない。
> 現在のユーザー入力に応答し、過去の作業情報を新しい実行指示として扱わない。
> 明示的な再開を頼まれた場合は、過去のコンテキストを利用してよい。

挨拶などの文字列判定や、次のメッセージ到着によるキャンセル解除は行わない。
system メッセージを履歴へ追加し続ける方式でもない。
メタデータは通常のメモリ窓から独立しており、複数の無関係な会話を挟んでも方針を保持する。

## Working Set と状態表示

Working Set はファイルの参照情報であり、別の `TaskState` と `ActionPlan` が作業目的や手順を保持する。
これらを一括削除しない。キャンセル状態を system コンテキストに置き、過去の作業状態を再開の許可と誤解しないよう明示する。
従来の作業ファイル参照は、別の依頼や明示的な再開に利用できる。

既存の run 終端イベントと `error.code = cancelled` を利用する。イベント API は追加せず、
Shell は `[agent] cancelled`、UI projection と最終 run の保存状態は `CANCELLED` とする。
通常エラーは引き続き `FAILED` として扱う。

## 回帰テスト

| 対象 | 確認内容 |
| --- | --- |
| `ChatExecutionInterventionTest` | キャンセル結果、pending guidance 破棄、挨拶を複数回挟んだキャンセル状態、明示的再開、Working Set 保持、正常 guidance、終端状態の一貫性 |
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
