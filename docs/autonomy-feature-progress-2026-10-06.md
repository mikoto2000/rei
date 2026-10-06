# 自律機能の継続作業記録（2026-10-06）

最初の継続7件に加え、後続の継続でSubAgent関連3件、共有Run報告token上限、Goal永続報告token上限を完了した。後続分は末尾の表に記録する。

この継続作業で次の7件を独立ブランチで実装し、Red→Green→関連・全体回帰→feature Commit/Push→main Merge→Merge後確認→main Pushを完了した。各実装の制限・追加候補を残し、全候補の完成とは区別する。初回からの一覧は[実装状況](autonomy-feature-audit.md)を参照。

| Status | 機能 / 実装レポート | Branch（codex/以下） | Feature Commit | main Merge | 全体回帰 tests / suites |
|---|---|---|---|---|---|
| Implemented | [Codex保存セッションの明示継続](implementation-report-external-review-continuation.md) | external-review-session-continuation | 4e198acd | 90314ae9 | 3012 / 574 |
| Implemented | [文書・図の編集案SubAgent](implementation-report-document-draft-agent.md) | document-draft-agent | 6b370060 | 338fd062 | 3014 / 575 |
| Implemented | [Paper Provider条件検査・ワークフロー](implementation-report-paper-provider-workflow.md) | paper-provider-workflow-validation | f086b754 | 7039b8b0 | 3016 / 576 |
| Implemented | [Codex修正案→Change Set→明示適用→再レビュー](implementation-report-external-fix-proposals.md) | external-review-fix-proposals | e80d08ba | 7610a988 | 3021 / 577 |
| Implemented | [週月の自動Coaching](implementation-report-automatic-period-coaching.md) | automatic-period-coaching | 6a2e5990 | 014a683d | 3026 / 578 |
| Implemented | [Skill選択のRun/Goal共通予算](implementation-report-skill-selector-run-budget.md) | skill-selector-run-budget | 32cf8cd5 | 7b1d21a0 | 3030 / 579 |
| Implemented | [SubAgent必須Toolの実応答条件](implementation-report-subagent-tool-outcome-contract.md) | subagent-tool-outcome-contract | 92f5110f | f6b3eac4 | 3035 / 580 |

Merged intoは全件main。各全体回帰はPASS（failure/error/skip各0）。最新の全体回帰はoffline Maven・JDK25・full profileで実行し、Merge後に各機能の関連テストも通過した。Java以外のクライアント実装を変更していないため、この継続ではNative/Reactテストを再実行していない。

最初の自動Coaching全体回帰では新しい設定キーにテンプレート厳密検査を合わせる必要があった。Skill予算の最初の全体回帰ではキャンセル試験のmockを予算付きAPIへ更新した。どちらも元の厳密性・停止条件を維持し、修正後に全体を再実行した。

## 実装した動作

- 保存された成功レビューのCLI UUIDだけを明示resumeできる。親結果は原子的に一度だけ継続でき、失敗・不明結果を自動再送しない。
- document-editorのreadonly編集案を既存の保存Change Setへ渡し、ユーザーの差分確認・明示Applyに接続する。既存テキスト・Mermaid・PlantUMLを扱う。
- Paper Providerが検索条件外の応答を返しても、年・OA・件数をアプリ側で照合する。Providerから保存・Session参照・要約・引用・再起動cacheまでfixture結合で確認する。
- Codexは単一ファイル修正案をreadonlyで生成し、親がProject/root/対象と完全baselineを検査してPROPOSEDを保存する。明示Apply後の再レビューは別Runとして行う。
- 自動Coachingは明示設定と保存済み利用基準の両方を必要とする。完了した週/月だけを対象に、共通cooldown・重複抑制・pause/busy等の条件を守り、最大1通知を既存Activity経路へ送る。
- 暗黙Skill選択のLLMを実呼出直前に共通予算へ予約し、枯渇・取消を停止経路へ伝える。候補なし／明示選択完了は追加消費しない。
- SubAgentのSUCCESSには、同じ実Tool応答の引用・JSON引数・任意expectedOutputの一致を要求できる。失敗・型違い・解析不能・切り詰めの結果は条件を満たさず、既存bounded修復へ接続する。

## Remaining

全対応の完了は宣言しない。監査表には意味検証の実モデル品質評価・別モデル合意、独立要約／Sleep／CLI／embedding/rerank予算、汎用Goal検証条件、意味的なActivity個人化、複数ファイル／binary文書／描画検証、外部通知・複数External Agentなどの追加候補が残る。既存の基本機能の完成と、これらの拡張候補は別に扱う。自由文の意味検証と読み取り系のSubAgent承認継承、明示Run経路の報告token上限とGoal跨ぎ永続報告token上限は以下の後続作業で実装した。

有料Providerや実LLMを使うPaper E2E、LLMの編集品質評価は実施していない。CLI継続は実機helpの対応確認とプロセスfixtureで検証し、実アカウントへreview/resumeは送っていない。これらを実施済みとする報告はしない。

## 後続のSubAgent関連3件

| Status | 機能 / 実装レポート | Branch（codex/以下） | Feature Commit | main Merge | 全体回帰 tests / suites |
|---|---|---|---|---|---|
| Implemented | [独立・上限付き意味検証](implementation-report-subagent-semantic-validation.md) | subagent-semantic-validation | 16cf77f4 | b596fe24 | 3043 / 581 |
| Implemented | [親の正確な一回承認の継承](implementation-report-subagent-parent-approval.md) | subagent-parent-approval | 93f8fd14 | fd0c6f14 | 3049 / 583 |
| Implemented | [通常Chatの共通Run予算](implementation-report-subagent-shared-run-budget.md) | subagent-shared-run-budget | e49b4f20 | 299a6fad | 3054 / 584 |

全件、独立ブランチでRed→Green→関連・全体回帰→feature Commit/Push→main Merge→Merge後確認→main Pushまで完了した。最新の全体回帰は3054 tests / 584 suites、failure/error/skip各0。Javaのみ変更したため、Native/Reactテストは今回再実行していない。

- semanticValidationは既定無効。決定的な実証跡検査の後に、原タスク・最終回答・実観測をToolなしの独立Promptで評価する。固定判定コード・入力上限・共有step/Goal予算/deadlineを守り、修復後も再検証する。実LLMの意味判断品質は未評価で、正しさの証明とはしない。
- inheritApprovalsは既定無効。Runnerが捕捉した親のみ参照し、READ/NETWORK_READ・Project/root/source一致時に正確な親Sessionの一回承認をSQLiteで消費する。未承認なら親所有の確認要求を保存する。DENY優先、並列一回競合、既定拒否と承認再利用の拒否を検証した。
- Goal外の通常Chatでは子LLMが親Run予算を消費していなかったため、既存共有予約を親カウンタ経由へ修正した。親・子・並列子・意味検証が共通Run回数を消費し、Goalにも二重計上しない。共通token上限とは区別する。

## 後続の共有Run報告token上限

| Status | 機能 / 実装レポート | Branch（codex/以下） | Feature Commit | main Merge | 全体回帰 tests / suites |
|---|---|---|---|---|---|
| Implemented | [共有Run報告token上限](implementation-report-shared-run-token-limit.md) | shared-run-token-limit | ef243dbf | 3c74d61c | 3064 / 587 |

Red→Green→関連・全体回帰→feature Commit/Push→main Merge→Merge後関連テスト→main Pushまで完了した。最新全体回帰は3064 tests / 587 suites、failure/error/skip各0。Javaのみ変更し、Native/Reactは再実行していない。

既定0で無効。有効時は親Chat・子・並列子・修復／意味検証・Skill選択・出力上限plannerのProvider報告totalTokensを同じRunへ計上する。超過応答のToolを実行せず、使用量不明時も停止する。予算本体の同期と8 workerの並列計上も検証した。応答後の停止条件であり、開始済み呼出しの請求上限やGoal全期間の上限を保証しない。詳しい適用範囲は[設定と制限](shared-run-token-limit.md)に記録した。

## 後続のGoal永続報告token上限

| Status | 機能 / 実装レポート | Branch（codex/以下） | Feature Commit | main Merge | 全体回帰 tests / suites |
|---|---|---|---|---|---|
| Implemented | [Goal永続報告token上限](implementation-report-persistent-goal-token-limit.md) | persistent-goal-token-limit | 76d3ef8e | 7dba0c53 | 3072 / 588 |

Red→Green→関連・全体回帰→feature Commit/Push→main Merge→Merge後関連テスト→main Pushまで完了した。最新全体回帰は3072 tests / 588 suites、failure/error/skip各0。Javaのみ変更し、Native/Reactは再実行していない。

既定0で無効。新規Goalの上限を作成時に保存し、使用量を複数Run・再開・再起動に跨いで累積する。未知usageや未報告予約は不明状態として保持し、reconcileで補充しない。親・子の二重計上拒否、Goal単独上限による実Chatの超過Tool非実行、旧DB移行、並列SQLite報告10組を検証した。未報告予約から次のattemptに進む所有権例外と、並列報告のSQLITE_BUSYを検出し、停止条件と同一トランザクション読み取りを修正後に全体回帰を再実行した。詳細は[設定と制限](persistent-goal-token-limit.md)に記録した。
