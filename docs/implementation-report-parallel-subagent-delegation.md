# Parallel SubAgent Delegation 実装報告

Branch: `codex/parallel-subagent-delegation`

単独delegateTaskに加えてCHAT用delegateTasksを登録した。最大8依頼、共有worker2、受付バッチ1、120秒の共通期限、全件preflight、入力順のtyped結果、部分失敗、親キャンセルを実装した。既存Runnerのモデル・権限・検証・repair・イベントを再利用し、子に新しいTool能力を与えない。

TDD Red: `target/parallel-subagent-red.log`（coordinator欠落）。テストは並列度・順序・親scope継承、全件事前検査、timeoutでのqueue停止、部分失敗と例外秘匿、親キャンセル・busy拒否、Tool入出力を検証する。実際のRunnerとCHATの統合テストで親履歴/Working Setの維持と新Toolの単一登録も確認する。新規6テストを含む関連テストが通過。全体回帰は2,792テスト / 537スイート、失敗・エラー・skipは0。Merge後の関連テストも実行する。

利用方法と制限は [parallel-subagent-delegation.md](parallel-subagent-delegation.md)。合意形成、依存DAG、永続復旧、全子共通token予算は未実装。
