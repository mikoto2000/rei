# SubAgent意味検証の実装レポート

## 実装

構造的な証跡照合だけでは残る、最終回答の根拠なし主張・矛盾・未達の成功報告を調べるopt-inの独立検証Promptを追加した。既存モデルport、BoundedToolLoop、実Tool証跡、共有予算・maxSteps・timeout・bounded repairを再利用する。旧定義・constructorは既定無効で互換。

Toolのない検証Promptで、原タスク・回答・観測だけを照合する。判定は固定コードのみ、入力・出力は上限を設け、判定不正時はfail closed。追加LLMを呼ぶため、既存の決定的検査を先に実施し、残り予算が足りなければ停止する。修復後も再検証し、元の診断を保持する。

## 検証

未実装YAMLのRed（2件失敗）を先に確認した。実Runnerの検証否定→修復→再検証、独立Prompt・Tool不許可、共有Goal予算・step上限、実観測の受渡し、不正判定の非公開、取消とdeadlineのstream dispose、入力上限、設定互換を検証する。関連テストと全体回帰3043 tests / 581 suitesはPASS（failure/error/skip各0）。feature Commit/Push→main Merge→Merge後確認→main Pushの順に実施する。実ハッシュはGit履歴と継続作業記録へ保存する。

## 範囲

同じモデルによる独立したPromptの評価であり、別モデル／人間との合意や意味の正しさの証明ではない。実LLMの品質評価は実施していない。判定をメモリ・Goal完了条件・承認へ自動昇格しない。ブランチはcodex/subagent-semantic-validation、baseはmain。
