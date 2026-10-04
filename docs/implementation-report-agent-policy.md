# Tool Permission Policy（第一段階）

Status: Implemented（能力判定と Tool 実行境界。対話中の approval/resume は未実装）

Branch: `codex/agent-policy`

Commit / Merge: Git 履歴の `feat: enforce configurable tool capability policy` / `Merge branch codex/agent-policy` を参照。

Merged into: `main`

Implemented:
- READ / LOCAL_WRITE / EXECUTE / NETWORK_READ / NETWORK_WRITE / EXTERNAL_SIDE_EFFECT / DESTRUCTIVE。
- AUTO_APPROVE / REQUIRE_APPROVAL / DENY。禁止優先、複合能力は全許可が必要。
- 管理者設定から immutable な分類・権限を取得。未知 Tool/任意 command は全能力として保守的に分類。
- Chat の StagnationChatModel と SubAgentRunner の callback 直前へ統合。
- 拒否時は所有権付きの既存 tool.failed イベント。引数・本文はイベントへ追加しない。
- 既定無効で既存動作を保持。既存個別 allowlist 等も維持。

Tests:
- Red: 判定・Chat 実行阻止テストの追加後、未実装クラスで compile failure を確認。
- Green: 判定、設定 binding、未知 Tool、禁止優先、immutable 設定、監査失敗を検証。
- Chat/SubAgent の callback 実行件数が0で、PermissionRequired が発行されることを検証。
- 関連 ToolPermissionPolicyTest / StagnationChatModelTest / SubAgentRunnerTest PASS。
- 全 Java suite: 2,682 tests / 514 suites、failure=0 / error=0 / skipped=0。
- `mvnw.cmd -o -Dmaven.repo.local=F:\project\rei\.m2\repository -Pfull test -q`、JDK25。
- diff review と `git diff --check` PASS。

Result: PASS

Remaining:
- 一回限りの承認 token、承認 UI、保留した Run の再開。
- 引数の path/URL/command 単位制約、slash command と背景処理への統合。
- 後続の Scheduler/Planning はこの追加範囲も整えてから自律的な副作用を増やす。

## 先行機能の Git 結果

Auto Sleep: `codex/auto-sleep`、commit `193b66c`、merge `e06b717`、main と feature を origin へ Push 済み。
Merge 後の関連4クラスも PASS。

## ブランチ調査補足

`feature/computer-use` は main にない旧試作コミットを含む。
main には別の computeruse/ 実装とテストが存在するため、旧 computer/ 実装の二重導入は対象外。
Work Context と Persistent Checkpoint は merge 済み。今回の2機能には既存未mergeブランチはなかった。

## 残項目の理由

外部環境や権限による未解消 blocker はない。
残機能は未実装/部分実装として自治機能調査表へ記載し、完成とは扱わない。
次に approval/resume と Waiting/dependency を整え、永続 Scheduler の dispatch・重複防止・missed run 方針を実装する。
これらは継続するユーザー承認と Project/Session 所有権の境界を共有するため、各機能を別の開発サイクルにする。
