# SubAgent validation repair 実装報告

Branch: `codex/subagent-validation-repair`

検証失敗をそのままFAILEDとしていたRunnerに、定義単位で最大3回の修復を追加した。既定無効。BoundedToolLoopは終了時の会話履歴と共有step counterを返せるよう拡張し、既存のstring APIを維持した。修復は元のTool履歴・証跡・モデル・権限を使い、共通maxStepsと単一timeoutの中で実行する。

TDD Red: `target/subagent-repair-red.log`（履歴・回数APIの欠落）。テストは修復成功、元のエラー保持、上限到達、共通予算の枯渇、provider障害の非retry、修復timeout、証跡再利用・Tool非再生、修復キャンセル、YAML設定の境界を検証する。新規6テストを含む関連テストが通過。全体回帰は2,786テスト / 536スイート、失敗・エラー・skipは0。Merge後にも関連テストを実行する。

詳細と保証範囲は [subagent-validation-repair.md](subagent-validation-repair.md)。自由文のsemantic判定、外部provider内部retryの制御、永続的なretry/resumeは未実装。
