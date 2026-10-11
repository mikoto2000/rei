# ChatGPT Plan Phase 2〜4 作業結果

Phase 2と3は実装・回帰・CI・PRマージ・専用worktree削除まで完了しました。Phase 4は現在のWindows sandboxで読取隔離を強制できないことを確認し、依頼書の停止条件に従って停止しました。Phase 4の研究draft PRは完了した機能のPRではありません。

## Git・検証結果

| 項目 | Phase 2 | Phase 3 | Phase 4 |
|---|---|---|---|
| ブランチ | feature/chatgpt-plan-phase2-auto-delegation | feature/chatgpt-plan-phase3-advanced-execution | feature/chatgpt-plan-phase4-app-server |
| 実装コミット | 781c6d25189111ad24086d09a9861f5b0b19da81 | 36121ef57eeb120accd49d0162a2f46210d1cc5f | 調査ec3d13241f59116f29676770b68c3562fb3aecba。報告追記後のHEADはphase4.json参照 |
| PR | [#99](https://github.com/mikoto2000/rei/pull/99) | [#101](https://github.com/mikoto2000/rei/pull/101) | [#102 draft](https://github.com/mikoto2000/rei/pull/102)、未完成の調査保存 |
| マージ | 6af8f4a0b873cf94bdd8223d20052f4dddac7cd1 | fec9d638608ae267d09e084e787719fdbe6c4024 | 未マージ |
| 全Java回帰 | 5,026件、failure/error 0、skip 2 | 5,036件、failure/error 0、skip 2 | Java変更なし。Phase 3結果を参照、Phase 4統合テスト未実装 |
| 追加テスト | 4件、関連28件成功 | 10件、関連63件成功 | 認証不要の実プロセス診断。機能受入テストではない |
| CI | 成功 | 成功 | draft PRのJava regressionは報告時点で実行中。最終snapshotはphase4.json参照 |
| 専用worktree | 削除済み | 削除済み | F:/project/rei/.worktrees/chatgpt-plan-phase4 に保持 |

Phase 2/3のcommitはorigin/mainに含まれることを確認しました。Phase 3はPhase 2のマージ・worktree削除後、Phase 4はPhase 3のマージ・削除後に開始しました。他のworktreeは保持しています。全回帰logとGit/検証metadataは ignored `F:/project/rei/.worktrees/chatgpt-plan-reports/phase2-full.log`, `phase3-full.log`, `phase2.json`, `phase3.json`, `phase4.json` に保存します。root checkoutのmainもorigin/mainの `2967ddcfbfd413110b363202453f1b28fbd2faa5` へfast-forwardしました。この最新tipには別PRの変更も含まれます。build.bat/build.shのSHA256が更新前後で同一であることを確認し、ユーザーの未コミット変更を保持しています。

## Phase 2の14項目

1. 実装: delegateTaskの任意backend/account/model/source/constraints/completion/expectedOutputフィールドと、opt-in自動委譲。親へ検証済み結果とusage/状態を返す。
2. 再利用: 既存role、PlanClient/PlanOAuth、Project source snapshot、exact-input承認、Run/共有予算、schema/引用/範囲/厳密完了条件、取消。
3. 主要ファイル: PlanConfiguration、SubAgentTools、SubAgentRunner、ChatGptSubAgentBackend、docs/chatgpt-plan-subagents.md。
4. 新規コンポーネント: PlanDelegationProperties。
5. 追加テスト: schema互換、実Run routing、無効/許可リスト、承認・共有予算・結果検証。
6. 件数/結果: 上表。REDを確認後GREEN、全回帰とCI成功。
7. 既存への影響: backend省略/local、旧Java overload、agent/task/context形式を維持。通常会話・CLI・Claudeの実行コードは変更しない。
8. 安全性: 既定無効、allowlist空は拒否、role限定、明示資料以外の隠れたcontext転送拒否、親権限/共有予算、zero tools、paid fallbackなし。
9. 未確認: 実アカウント/実推論、任意自由文の完了条件の正しさ。本人認証は代行していない。
10〜14. ブランチ、commit、PR、merge、worktree削除: 上表。

詳しい既存差分とTDDは [Phase 2報告](implementation-report-chatgpt-plan-phase2.md) に記載しています。

## Phase 3の14項目

1. 実装: 明示履歴input、同一期限/共有予算内の出力修復と再検討、機械的な意味/根拠補助検証、混在/複数モデルの並列比較と部分失敗保持。
2. 再利用: 同じ出力検証器/資料snapshot/承認、既存2worker/8queue/120秒batch、AgentRunScope、各roleのmaxSteps/maxRepairs/timeout、Run/Goal予約。
3. 主要ファイル: PlanClient、PlanConfiguration、ChatGptSubAgentBackend、ParallelSubAgentDelegator、SubAgentTools、関連tests、利用ガイド。
4. 新規コンポーネント: PlanExecutionProperties、PlanReviewSemanticValidator、PlanReviewComparison。
5. 追加テスト: 履歴1、backend修復/再検討/非再試行/修復上限/期限5、意味検証2、混在部分失敗1、実Run scope伝播1。
6. 件数/結果: 上表。RED/GREENと関連回帰、全回帰、CI成功。
7. 既存への影響: 高度化は既定無効。旧Request/Item/Batch constructor、local dispatchとworker基盤を維持。
8. 安全性: role/configの小さい上限、全推論usage計上、無効出力も計上、unknownを成功にしない。認証/取消/quota/権限/通信unknownの自動再試行・別課金先fallbackなし。
9. 未確認: 実OAuth/実推論、自然言語の真偽・矛盾全般。truthVerifiedは常にfalse。多数決や自己評価だけでは確定しない。
10〜14. ブランチ、commit、PR、merge、worktree削除: 上表。

詳しい検証範囲と互換性は [Phase 3報告](implementation-report-chatgpt-plan-phase3.md) に記載しています。

## Phase 4の14項目

1. 実施: 既存CLIとapp-serverの差分調査、0.154.0の通常/実験schema生成、資格情報なしRPC実プロセス、sandbox境界の拒否確認。機能実装前に停止。
2. 再利用予定: existing Project/Session/Run、DelegationService/明示認可、Permission、共有予算、隔離実装/変更レビュー、schema/根拠検証。
3. 主要ファイル: implementation-report-chatgpt-plan-phase4.md、docs/probes/codex_app_server_permissions.py、利用ガイド、今回の報告。
4. 新規コンポーネント: 診断スクリプトのみ。production backendなし。
5. 追加テスト: no-credential実プロセス診断。要求された通常CIのmock統合テストは未実装。
6. 結果: version/initialize/profile一覧は成功。限定read profileのthread/startはRPC -32603拒否。Turnなし、process exit 0、scriptは期待する診断exit 2。
7. 既存への影響: production code変更なし。既存 `/agent codex` / Claude / Plan経路を維持。
8. 安全性: own isolated CODEX_HOME、子環境限定、OAuth/APIキーなし、通常Codexログイン状態を使わず、native stderrを保存しない。全ディスクreadで回避しない。
9. 未確認: 別sandbox/OS/version、認証連携、refresh/logout、実Turn、tool permission bridge、usage、取消・unknown、独立結果検証。
10〜14. 上表と最終報告/phase4.json。未マージ、worktree保持。

[停止根拠・再現・再開条件](implementation-report-chatgpt-plan-phase4.md) を参照してください。

## 現時点の利用可能な機能と使い分け

| 経路 | 用途・認証 | 手動/自動 | ツールと制約 |
|---|---|---|---|
| local SubAgent | 既存Spring AI/role設定 | 既存手動・delegateTask(s) | role/Policy許可ツール、既存証拠/修復/予算 |
| chatgpt-plan Phase 0/1 | own SIWC登録/refresh/logout/catalog、資料レビュー/相談 | `/subagent run ... --backend chatgpt-plan` | 明示資料snapshotと1回HTTP推論、zero tools |
| chatgpt-plan Phase 2 | 親が明示backend/account/modelを指定 | opt-in delegateTask(s) | 自動allowlistと既存送信承認/親Run予算 |
| chatgpt-plan Phase 3 | 複数回推論/修復/再検討/混在・複数モデル比較 | 同じ手動/自動入口、execution opt-in | 機械的意味補助検証、部分失敗、truthVerified=false |
| Codex CLI | 従来の外部レビュー・明示隔離実装 | 既存 `/agent codex ...` | CLI自身の認証と既存隔離/能力検査/変更レビュー |
| Codex app-server Phase 4 | イベント/Thread/Turn管理を伴う作業を予定 | 未提供 | 現Windowsの限定read境界で停止 |
| Claude Code | 従来の外部委譲 | 既存 `/agent claude ...` | 既存明示認可・隔離・結果検証 |

手動のstandalone委譲は既存入口の独立予算を使います。親の自動委譲は実ToolContextのProject/Session/Runと共有予約を引き継ぎます。親は子の意見の採用/部分採用/不採用を独立判断し、外部見解を自身の判断と区別します。自動化設定だけで外部送信承認を省略できません。

未対応: app-server実行、任意自由文の完了の独立証明、自然言語の真偽保証、Planのツール実行、roleに必要なtool evidence/既存LLM semantic契約の代替、有料judgeや別アカウントへの自動fallback。改善候補は強制sandbox準備とPhase 4、明示予算内の独立judge、より精密な根拠比較です。これらは今回完了した機能に含めません。
