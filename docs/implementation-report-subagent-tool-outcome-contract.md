# SubAgent Tool応答契約の実装レポート

## 問題と変更

既存requiredToolCallsは実行Tool・引数・引用を照合していたが、実Toolが失敗結果を返しても呼出し自体が存在すればSUCCESSを満たせた。任意のexpectedOutputを追加し、引数と同じ実応答の指定フィールドを独立照合する。既存定義は互換、追加LLMなし、権限拡張なし。

保持上限を超えた応答・不正JSON・値型の不一致・引数と別応答の混合を拒否する。定義は非空JSONオブジェクト・4096文字に制限する。既存の上限付き修復処理で診断履歴を保持する。

## 検証

未実装APIのRed後、証跡の実応答一致／失敗応答／型／重複キー／余分なJSON／切り詰め、YAMLの型・未知フィールド、実RunnerでSUCCESS拒否→PARTIAL修復と元診断保持を検証した。関連テストと全体回帰3035 tests / 580 suitesはPASS（failure/error/skip各0）。Merge後にも関連テストを確認する。

## Gitと範囲

独立ブランチcodex/subagent-tool-outcome-contractを使用し、feature Commit/Push→main Merge→Merge後確認→main Pushの順で実施する。ハッシュはGit履歴に記録する。自由文の意味判断・Tool出力そのものの真偽・任意コマンドの成功判定は追加候補として残す。
