# SubAgent実行証跡検証 実装報告

Branch: `codex/subagent-evidence-validation`

JSON Schemaだけでは捏造した根拠や未実施ToolのSUCCESS報告を検知できなかった。定義単位のopt-in契約、Runner由来のbounded証跡、引用・ハッシュの照合、SUCCESSのrequired Tool観測条件を追加した。新しいフレームワークやLLM judgeは追加せず、既存の構造検証・制限・Permission・失敗伝播を再利用する。

TDD Red: `target/subagent-evidence-red.log`（検証クラス欠落）。新規テストは照合成功、未実施Tool、別Run、偽の引用・ハッシュ、重複証跡、PARTIAL、長い応答の切り詰め、不正YAML、Runnerの正常完了と検証失敗を確認。関連テストの全件が通過。全体回帰は2,780テスト / 536スイート、失敗・エラー・skipは0。Merge後にSubAgent関連テストを再実行する。

利用方法と保証範囲は [subagent-evidence-validation.md](subagent-evidence-validation.md)。自由文全体のsemantic判定・特定Tool引数のrequired task・repair/retryは未実装。
