# 自律機能の継続作業記録（2026-10-06）

最初の継続7件に加え、後続の継続でSubAgent関連3件、共有Run報告token上限、Goal永続報告token上限、Run内context要約token計上、Sleep共有モデル予算、Git変更の自動収集によるテスト影響分析、保存済みJUnitレポート診断、HTTP本文SHA依存待機、HTTP JSON値依存条件を完了した。後続分は末尾の表に記録する。

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

全対応の完了は宣言しない。監査表には意味検証の実モデル品質評価・別モデル合意、RunContextなしの要約／Sleep跨ぎ永続予算・旧consolidate/summarize／CLI／embedding/rerank予算、汎用Goal検証条件、意味的なActivity個人化、複数ファイル／binary文書／描画検証、外部通知・複数External Agentなどの追加候補が残る。既存の基本機能の完成と、これらの拡張候補は別に扱う。自由文の意味検証と読み取り系のSubAgent承認継承、明示Run経路の報告token上限、Goal跨ぎ永続報告token上限、Run内context要約token計上は以下の後続作業で実装した。

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

## 後続のRun内context要約token計上

| Status | 機能 / 実装レポート | Branch（codex/以下） | Feature Commit | main Merge | 全体回帰 tests / suites |
|---|---|---|---|---|---|
| Implemented | [context要約の共有token計上](implementation-report-context-summary-token-budget.md) | context-summary-token-budget | dbf29060 | 53ce0a61 | 3079 / 589 |

Red→Green→関連・全体回帰→feature Commit/Push→main Merge→Merge後関連テスト→main Pushまで完了した。最新全体回帰は3079 tests / 589 suites、failure/error/skip各0。Javaのみ変更し、Native/Reactは再実行していない。

要約は既存の呼出回数予算を既に消費していたため、usageの集約後にRunへ報告する処理を追加した。Goal単独上限でも永続使用量へ渡る。超過／不明をfallbackに隠さず、要約で上限へ達した親を開始しない。不採用・length応答でも既知usageを保持し、timeoutのProvider取消、既存キャンセル、超過／不明の非保存と上限ちょうどの保存を確認した。新しい設定・予算DB・Runは追加していない。詳しい範囲は[context-summary-token-budget.md](context-summary-token-budget.md)。

## 後続のSleep共有モデル予算

| Status | 機能 / 実装レポート | Branch（codex/以下） | Feature Commit | main Merge | 全体回帰 tests / suites |
|---|---|---|---|---|---|
| Implemented | [Sleep共有モデル予算](implementation-report-sleep-model-budget.md) | sleep-model-budget | 3cc0df7c | b896f949 | 3089 / 590 |

初期Red→Green→関連・全体回帰→feature Commit/Push→main Merge→Merge後関連テスト→main Pushまで完了した。全体回帰は3089 tests / 590 suites、failure/error/skip各0。Javaのみ変更し、Native/Reactは再実行していない。

既定0で無効。1回のSleep内の抽出・意味解決を同じ回数／報告token予算へ接続した。超過・不明usageでは記憶を部分保存せず、処理済み位置も進めない。完全一致などの決定的処理はモデルを消費しない。複数Sleepを跨ぐ永続上限や既存Auto Sleepの再試行禁止は今回の範囲外。詳しい設定と制限は[sleep-model-budget.md](sleep-model-budget.md)。
## 後続のGit変更の自動収集

| Status | 機能 / 実装レポート | Branch（codex/以下） | Feature Commit | main Merge | 全体回帰 tests / suites |
|---|---|---|---|---|---|
| Implemented | [Git変更の自動収集](implementation-report-git-change-test-impact.md) | git-change-test-impact | 61e27783 | 3b6c33ce | 3096 / 591 |

初期Red→Green→関連・全体回帰→feature Commit/Push→main Merge→Merge後関連テスト→main Pushまで完了した。全体回帰は3096 tests / 591 suites、failure/error/skip各0。Javaのみ変更し、Native/Reactは再実行していない。

changeTestImpactのchangedFiles省略時に、捕捉Projectのステージ済み・未ステージ・未追跡pathを収集する。削除／rename／空白・日本語path／ignore／ステージと作業ツリーの相殺／Tool callbackを実Gitで確認し、失敗・切り詰め・取消を隠さない。変更収集の上限・除外によるpartial・構造評価の限界を保持する。coverageや完全な意味的依存解析は引き続き残件。
## 後続の保存済みJUnitレポート診断

| Status | 機能 / 実装レポート | Branch（codex/以下） | Feature Commit | main Merge | 全体回帰 tests / suites |
|---|---|---|---|---|---|
| Implemented | [JUnitレポート診断](implementation-report-junit-report-diagnosis.md) | junit-report-diagnosis | 2ac395cd | 9ab70cdb | 3104 / 592 |

初期Red・擬似testcase混入の振る舞いRed→Green→関連・全体回帰→feature Commit/Push→main Merge→Merge後関連テスト→main Pushまで完了した。全体回帰は3104 tests / 592 suites、failure/error/skip各0。Javaのみ変更し、Native/Reactは再実行していない。

保存済み単一JUnit XMLのfailure/error/skippedと診断文をREAD Toolで確認する。報告countと実観測count、SHA・時刻・partialを区別し、現在processやGoalの成功を断定しない。XML外部参照禁止・境界／上限・認証情報除去・実Tool callback・SubAgent明示要求を検証した。完全原因特定・その他形式・自動発見／修復は引き続き残件。
## 後続のHTTP本文SHA依存待機

| Status | 機能 / 実装レポート | Branch（codex/以下） | Feature Commit | main Merge | 全体回帰 tests / suites |
|---|---|---|---|---|---|
| Implemented | [HTTP本文SHA依存待機](implementation-report-http-body-dependency.md) | http-body-dependency | bdc1b3cf | 7eece12b | 3110 / 593 |

初期Red・永続reason契約の結合エラー検出→修正Green→関連・全体回帰→feature Commit/Push→main Merge→Merge後関連テスト→main Pushまで完了した。全体回帰は3110 tests / 593 suites、failure/error/skip各0。Javaのみ変更し、Native/Reactは再実行していない。

status＋完全本文SHA-256一致を既存依存条件へ追加し、最大64KiB・2秒・逐次hash・本文非保存を守る。SQLite再生成、旧port未対応拒否、ネットワークTool境界、標準Policyで自動通信なし・NETWORK_READ許可したwatcherの実GET、途中切断・本文受信中取消、terminal waitの追加requestなしをloopbackで検証した。任意code／JSON field／部分文字列条件や外部実サービス品質評価は引き続き残件。
## 後続のHTTP JSON値依存条件

| Status | 機能 / 実装レポート | Branch（codex/以下） | Feature Commit | main Merge | 全体回帰 tests / suites |
|---|---|---|---|---|---|
| Implemented | [HTTP JSON値依存条件](implementation-report-http-json-dependency.md) | http-json-dependency | 933bcc87 | 706102f5 | 3116 / 594 |

初期Red・近接小数の誤一致を振る舞いRedで検出→修正Green→関連・全体回帰→feature Commit/Push→main Merge→Merge後関連テスト→main Pushまで完了した。全体回帰は3116 tests / 594 suites、failure/error/skip各0。Javaのみ変更し、Native/Reactは再実行していない。

固定JSON Pointerのscalarとstatusを検証する。型・nullと欠落・BigDecimal精度、strict JSONとbyte／depth上限、SQLite再生成、ネットワーク境界と許可済み自動監視、redirect・途中切断・受信中取消、terminal waitの再GETなしをloopbackで確認した。本文・実値・parser診断を保存せず、既存SHA/status条件も回帰確認した。任意code・正規表現・複合条件・外部サービス品質評価は残件。
## 後続の文書BM25検索

| Status | 機能 / 実装レポート | Branch（codex/以下） | Feature Commit | main Merge | 全体回帰 tests / suites |
|---|---|---|---|---|---|
| Implemented | [文書BM25検索](implementation-report-document-bm25.md) | document-bm25 | aa1f38aa | 7aef5f4e | 3122 / 594 |

初期Red・実DB検証・設定bindingの結合エラー検出→修正Green→関連・全体回帰→feature Commit/Push→main Merge→Merge後関連テスト→main Pushまで完了した。全体回帰は3122 tests / 594 suites、failure/error/skip各0。Javaのみ変更し、Native/Reactは再実行していない。

明示opt-inのSQLite FTS5/BM25を既存RRF・文書集約・rerankへ統合した。既存DBのbackfill、全更新経路のtransaction同期、無効設定時の既存索引維持、置換失敗rollback、source/docId・coverage閾値、literal query、取消と設定bindingを検証した。学習済みsparse、多言語tokenizer、実データ品質評価は残件。
## 後続のJUnitレポート自動発見・集合診断

| Status | 機能 / 実装レポート | Branch（codex/以下） | Feature Commit | main Merge | 全体回帰 tests / suites |
|---|---|---|---|---|---|
| Implemented | [JUnitレポート自動発見・集合診断](implementation-report-junit-report-discovery.md) | junit-report-discovery | d3f36e73 | a811ac66 | 3130 / 595 |

初期Red→最小Green→上限／境界／実Tool検証→関連・全体回帰→feature Commit/Push→main Merge→Merge後関連テスト→main Pushまで完了した。全体回帰は3130 tests / 595 suites、failure/error/skip各0。Javaのみ変更し、Native/Reactは再実行していない。

標準Maven/Gradle配置のbounded自動発見と明示directoryの集合診断を追加した。各file identity、解析済みlong合計、missing／未読／重複／partial、共有の証拠上限、実Windows junctionのProject外拒否、Policy・SubAgent明示要求を検証した。現在process／Goalの成功を判定しない。その他形式・完全原因特定・自動repairは残件。
## 後続のSemantic Skill永続索引

| Status | 機能 / 実装レポート | Branch（codex/以下） | Feature Commit | main Merge | 全体回帰 tests / suites |
|---|---|---|---|---|---|
| Implemented | [Semantic Skill永続索引](implementation-report-persistent-skill-index.md) | persistent-skill-index | ccfa7adf | 4a7c2740 | 3140 / 596 |

初期Red・空catalog掃除／read-only旧次元の回復閉塞を振る舞いRedで検出→修正Green→関連・全体回帰→feature Commit/Push→main Merge→Merge後関連テスト→main Pushまで完了した。全体回帰は3140 tests / 596 suites、failure/error/skip各0。Java/configのみ変更し、Native/Reactは再実行していない。

明示opt-inのSQLite metadata vector snapshotを追加した。再起動cache、profile SHA・model/version namespace、現在Skill返却・変更／削除掃除、破損miss・transaction rollback・DB障害・次元変更回復・取消と生成設定を検証した。query・Skill本文・raw profileを保存しない。学習／実データ品質評価とembedding/rerank共有費用予算は引き続き残件。
## 後続の旧Memory統合・要約モデル予算

| Status | 機能 / 実装レポート | Branch（codex/以下） | Feature Commit | main Merge | 全体回帰 tests / suites |
|---|---|---|---|---|---|
| Implemented | [旧Memory統合・要約モデル予算](implementation-report-memory-consolidation-budget.md) | memory-consolidation-budget | ba978fb3 | 0bc57970 | 3149 / 597 |

初期Red→実ChatClient/SQLite最小Green→保存・usage・取消・境界の検証→関連・全体回帰→feature Commit/Push→main Merge→Merge後関連テスト→main Pushまで完了した。全体回帰は3149 tests / 597 suites、failure/error/skip各0。Java/configのみ変更し、Native/Reactは再実行していない。

1操作内の抽出と要約で呼出回数／報告token上限を共有し、未知／超過は解析・fallback・保存前に停止する。実Picocli --approveで停止後のMemoryService非接触、上限ちょうどの成功／次呼出抑制、正常保存、provider障害、取消、独立設定binding・既定互換を検証した。Sleep跨ぎ永続予算・CLI／embedding／rerank等の費用予算は引き続き残件。
## 後続のSleepプロジェクト累積予算

| Status | 機能 / 実装レポート | Branch（codex/以下） | Feature Commit | main Merge | 全体回帰 tests / suites |
|---|---|---|---|---|---|
| Implemented | [Sleep累積予算](implementation-report-sleep-persistent-budget.md) | sleep-persistent-budget | 6b8b0438 | 43f5ea06 | 3159 / 598 |

初期Red→実SQLite／実Sleep経路Green→関連・全体回帰→feature Commit/Push→main Merge→Merge後関連テスト→main Push済み。failure/error/skip各0。失敗・preview費用の保持、unknown／pending停止、再起動・並行予約・設定bindingを検証した。CLI／embedding／rerankなどの費用予算は残件。

## 後続のAuto Sleep保存済みmetadata再検出

| Status | 機能 / 実装レポート | Branch（codex/以下） | Feature Commit | main Merge | 全体回帰 tests / suites |
|---|---|---|---|---|---|
| Implemented | [metadata定期再検出](implementation-report-auto-sleep-metadata-refresh.md) | auto-sleep-metadata-refresh | 944cdb6d | 6b5e54a3 | 3165 / 599 |

実ファイルでの外部追加未検出Red→Green→6テストでページ／復旧／idle／project境界→関連・全体回帰→feature Commit/Push→main Merge→Merge後関連テスト→main Push済み。failure/error/skip各0。終了triggerは残件。

## 後続のRunContextなし会話要約予算

| Status | 機能 / 実装レポート | Branch（codex/以下） | Feature Commit | main Merge | 全体回帰 tests / suites |
|---|---|---|---|---|---|
| Implemented | [RunContextなし要約予算](implementation-report-standalone-context-summary-budget.md) | standalone-context-summary-budget | f25a564e | 87d892d5 | 3172 / 599 |

初期Red→Green→7テストで履歴／実行ログ共有・未知usage・timeout・保存・親予算優先・設定→関連・全体回帰→feature Commit/Push→main Merge→Merge後関連テスト→main Push済み。failure/error/skip各0。CLI／embedding／rerankの費用予算は残件。

## Sleep累積予算の0 token報告修正

| Status | 機能 / 実装レポート | Branch（codex/以下） | Feature Commit | main Merge | 全体回帰 tests / suites |
|---|---|---|---|---|---|
| Implemented | [非正値usage修正](implementation-report-sleep-empty-usage.md) | sleep-persistent-empty-usage | cdbcdd85 | 1b1404b6 | 3174 / 599 |

2 failure / 0 errorのRed→Green→関連・全体回帰→feature Commit/Push→main Merge→Merge後関連テスト→main Push済み。failure/error/skip各0。累積token上限だけを有効にした場合も非正値usageで停止し、永続unknownを保持する。

## 後続の外部Codex CLI親モデル予算

| Status | 機能 / 実装レポート | Branch（codex/以下） | Feature Commit | main Merge | 全体回帰 tests / suites |
|---|---|---|---|---|---|
| Implemented | [CLI親モデル予算](implementation-report-codex-run-model-budget.md) | codex-run-model-budget | 5b397165 | eb46a810 | 3183 / 600 |

初期Red→Green→9テストでCLI usage／Goal永続化／未知・超過／取消／保存前停止／既定互換→関連・全体回帰→feature Commit/Push→main Merge→Merge後関連テスト→main Push済み。failure/error/skip各0。CLI内部cycleの回数制限ではなく、1委譲の報告費用を親へ累積する。embedding／rerank費用予算などは残件。

## 後続のembedding親モデル予算

| Status | 機能 / 実装レポート | Branch（codex/以下） | Feature Commit | main Merge | 全体回帰 tests / suites |
|---|---|---|---|---|---|
| Implemented | [embedding親モデル予算](implementation-report-embedding-run-model-budget.md) | embedding-run-model-budget | 934880b4 | efedc73a | 3192 / 601 |

初期Red→Green→実ストアのtyped停止変換Red→修正→9テスト・関連・全体回帰→feature Commit/Push→main Merge→Merge後関連テスト→main Push済み。failure/error/skip各0。Tool／Skill／worker／Goal永続化・vector保存抑止を検証した。rerank費用予算などは残件。
