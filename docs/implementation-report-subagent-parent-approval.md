# SubAgent親承認継承の実装レポート

## 実装

SubAgentのToolがREQUIRE_APPROVALになると、親の承認を使用できず、確認要求も残せなかった。定義ごとの既定無効inheritApprovalsを追加し、Runnerの捕捉した親Sessionを既存ToolPermissionGuardへ渡す。読み取り系・Project/root/source一致だけを対象とし、既存SQLiteの正確な一回限り承認を消費する。

承認がない場合は親Sessionで確認できる要求を残す。DENYは常に優先し、書き込み系・所有者不一致は継承しない。子のTool policyを広げず、旧constructor・定義は互換。

## 検証

未実装APIのRedを確認後、実SQLiteで一回消費、入力・Project/root/会話境界、書込拒否、親所有の要求、DENY優先、並列子の一回競合を検証した。実Runnerでも明示設定なし→拒否・設定あり→成功・再試行→拒否を確認し、YAMLの型と実行Tool禁止を確認した。関連テストと全体回帰3049 tests / 583 suitesはPASS（failure/error/skip各0）。実Commit/MergeハッシュはGit履歴と継続記録へ保存する。

## Gitと範囲

ブランチcodex/subagent-parent-approval、base main。feature Commit/Push→main Merge→Merge後確認→main Pushの順で実施する。既存Shell/Nativeの確認操作を再利用し、新しい画面は追加しない。停止した子の自動再開や書込み系の承認継承は対象外。
