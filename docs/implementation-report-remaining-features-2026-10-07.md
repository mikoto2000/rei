# 未実装・部分実装機能の再監査・実装記録

## 基準と作業状態

2026-10-07（日本時間）、`git fetch origin main` 後の基準は
`905cd4ee0e4eaf1c110337225ef48a401bbc9c06`。依頼文の `c5f3d88a` より新しく、
Spring Boot / Spring AI の更新を含む。開始時のローカルmainとorigin/mainは一致した。
元checkoutにはLLMタイムアウト関連の変更2件、追加Java/test各1件、引き継ぎ文書1件がある。
これらを変更・commitせず、`.worktrees/remaining-features` に
`codex/remaining-features-audit` を作成した。

本書は進行中の記録である。下表は開始時判定であり、完成報告ではない。
テストファイルの存在と今回の実行成功を区別する。

## 開始時再監査

既存の6候補資料、Git履歴、Java production/test、Native Rust、Reactを照合した。
`IMPLEMENTED` は今回の要求範囲が満たされる場合だけ使用する。
関連基盤があるだけの場合は `PARTIAL` とする。

| 対象 | Before | 確認した基盤・経路・既存テスト | 今回必要な不足 |
|---|---|---|---|
| P1 会話並行化 | PARTIAL | ChatController → ChatSubmitService → SessionLifecycle → ConversationInputRouter → ProjectRunQueue。ProjectRunQueueTest、SessionAdmissionTest、RunCancellationTest。ShellはsubmitNewRunと介入mailboxを区別 | Project内全RunがFIFO。Native service.submitとReact canSubmitが同会話のactive Runを拒否。READの安全な並行実行、WRITEとの競合、永続Run復元が不足 |
| P2 Task Manager | PARTIAL | RunRegistry/RunService、GoalController、ScheduleController、DependencyController、Checkpoint。RunRegistryTest、GoalHttpTest、ScheduleHttpTest | RunRegistryはメモリのみ、終端保持30分。横断projection/pagination/Native一覧が不足。task.TaskServiceはGoogle Tasks用でありAgent Task Managerではない |
| P3 Artifact Delivery | PARTIAL | BackgroundRunSubmitService.imageは.rei/web-imagesへ出力、PaperArtifactStoreは専用保存、TextChangeSetServiceは差分保存。BackgroundRunApiTest、TextChangeSetTest | 共通Artifact metadata/所有者/認証取得API/hash検証/Native preview/downloadが不足。任意path取得で補完しない |
| P4 GitHub Event Trigger | MISSING | AgentEventTriggerService → PersistentAgentScheduler.signalEvent → AgentScheduleDispatcher、PersistentAgentSchedulerTest | GitHub署名endpoint、delivery永続重複抑制、repository/filter/mapping、Inbox/Dependencyへのadapterが不足 |
| P5 Cross-project Today | PARTIAL | WorkContextController、Goal/Dependency/SchedulerとActivity基盤、WorkContextControllerTest | 明示Project allowlistの横断Today集計、分類、HTTP/Native公開が不足 |
| P6.1 Codex implementation | PARTIAL | ExternalAgentDelegationService/Router、CodexExternalAgentExecutor、ExternalFixProposalTest、ParallelCodexReviewsTest | 保存review/fix/単一Change Setは存在。isolated worktree実装、親独立検証、receipt、明示取り込みが不足 |
| P6.2 Claude拡張 | PARTIAL | ClaudeCodeExternalAgentExecutor/ProcessRunner、RoutingExternalAgentExecutor、ClaudeCodeReviewsTest。/agent claude review | subscription/OAuth snapshot reviewのみ。native resume、fix/Change Set、parallel batch、isolated implementationが不足 |
| P7.1 Durable child | PARTIAL | SubAgentRunner、ParallelSubAgentDelegator、共通Run/Goal予算、ParallelSubAgentDelegatorTest | 子ID/ownership/checkpoint/予約/未知Toolの永続復元が不足。親Checkpointを子resumeとみなさない |
| P7.2 子DAG | MISSING | 独立並列batchとDependency DAGはある | 子実行dependencies/fan-in/restart/cycle/fail policyのcontractが不足 |
| P7.3 Consensus | MISSING | SubAgentResultValidator/SubAgentSemanticValidator、SubAgentSemanticValidationTest | 独立回答の比較、disagreement/unresolved/provenance、任意judgeの予算が不足 |
| P8 Predicate | PARTIAL | DependencySourceProbe/HttpJsonCondition、FileGoalVerifier/JsonFileGoalCondition、HttpJsonDependencyTest/JsonFileGoalTest | 宣言的AND/OR/NOT/比較/MATCH、schema/version/complexity上限が不足 |
| P9 Reflection一般化 | PARTIAL | RunReflectionService/Repository、VerifiedReflectionMemoryService、VerifiedReflectionMemoryTest | 既存gap/候補とPROJECT_STATE proofを維持。教訓状態、反例/count/freshness/訂正を持つ意味一般化が不足 |
| P10 Notification provider | PARTIAL | AttentionDeliveryService/Repository/JdkAttentionSender、AttentionDeliveryTest。Inbox→既定OFF webhook outbox | provider boundaryとSlack transport、receipt/rate limitが不足。実送信は別途credential/送信許可が必要 |
| P11 多言語Map | PARTIAL | RepositoryMapServiceはJDK Java AST、SqliteRepositoryMapIndex、ChangeTestImpactService。RepositoryMapServiceTest | 非Javaはinventory。TS/JS/Rust/Go/Pythonの解析・逆依存・build境界とheuristic表示が不足 |
| P12 Coverage | MISSING | ChangeTestImpactServiceは構造候補のみ、既存文書もcoverage未対応と明記 | JaCoCo/LCOV/Coberturaの実report、changed-line結合、未観測の区別が不足 |
| P13 Diagnosis→Repair | PARTIAL | BuildTestFailureDiagnosis、SelfPatchReviewService/SelfPatchRepairService、JUnit診断/保存fix apply/再検証テスト | Diagnosis evidence→repair proposal→Change Setの明示接続が不足。既存最大3修正/180秒制限を再利用 |
| P14 Semantic patch review | PARTIAL | SelfPatchReviewServiceの同一patch/test/static checks | requirement/test/evidence比較、optional semantic providerが不足。LLMだけでpassにしない |
| P15 Goal gate | PARTIAL | GoalRepository.criteria、GoalLoopService/FileGoalVerifier、MultiFileGoalTest/GoalRecoveryTest | SHA/JSON条件は存在。required tests/artifacts/reviewと全Goal適用設定が不足 |
| P16 Multi-file Change Set | PARTIAL | TextChangeSetService/Repository、TextChangeSetTest。単一既存UTF-8、SHA、claim、明示Apply、unknown保護 | 複数baseline/whole hash、create/delete/rename、transaction/rollback/recoveryが不足 |
| P17 Renderer | PARTIAL | document-editorはMermaid/PlantUMLテキスト編集案を保存、DocumentDraftAgentTest | renderer実行/parse/Artifact/hash receipt検証が不足。binary汎用編集を完成扱いしない |
| P18 RAG/Skill評価 | PARTIAL | HybridRetriever/ReciprocalRankFusion/SemanticSkillSearch、HybridRetrievalTest/SemanticSkillSearchTest | relevance/hard-negative fixture、Recall/Precision/MRR/nDCG比較harnessが不足 |
| P19 Learned Sparse | MISSING | SQLite BM25/FTS5とdense/RRF fallbackがある | SparseEncoder境界と未設定時fallback。live provider可用性は未確認 |
| P20 semantic validation評価 | PARTIAL | SubAgentSemanticValidatorのToolなし判定、strict verdict、予算、SubAgentSemanticValidationTest | correct/subtle/unsupported/contradictory/insufficient fixtureとTP/FP/FN/abstention指標が不足 |
| P21 Activity/Coaching評価 | PARTIAL | Activity fixture、PeriodCoaching、DailySummaryWordingTest、分類/unknown/観測品質gate | 今回の統一品質harness、匿名fixture/曖昧/gapと根拠・確認済み事実の分類評価が不足 |
| P22 live E2E | MISSING | ExternalAgentEvaluationIntegrationTest等はfixture結合。既存reportはClaude/Paper live未確認 | 既定OFF live switches、前提不足の理由付きskip、通常CIとの分離が不足 |
| Crash consistency | PARTIAL | Goal/Scheduler/Dependency/Checkpoint/outboxの永続claim/復旧、GoalRecoveryTest/ScheduleRecoveryTest/CheckpointRestartSmokeTest | Run/子/新Artifact/multi-file操作も含むcrash-window検証。hard kill hook保証は要求しない |

外部環境による未検証は実装不足と別に記録する。Codex/Claude native CLI認証・resume・実装、
Paper実provider、Slack実配送、有料モデル品質は現時点で確認していない。
fixture/local harnessの不足をcredential不足として免除しない。

## 既存10機能との照合

| 対象 | 開始時状態 | 利用経路・今回の判断 |
|---|---|---|
| Scheduler | IMPLEMENTED | PersistentAgentScheduler/Dispatcher、/schedule、ScheduleController、Native Scheduler。ScheduleRecoveryTest等。今回変更不要（GitHub adapterは別の不足） |
| Notification Inbox | IMPLEMENTED | AttentionService/Repository、/attention、AttentionController、Native Inbox。AttentionServiceTest。今回変更不要（providerは別） |
| Long task中別会話 | PARTIAL | P1参照 |
| Task Manager | PARTIAL | P2参照 |
| Artifact Delivery | PARTIAL | P3参照 |
| Approval/Permission | IMPLEMENTED | ToolPermissionPolicy/Guard/ApprovalRepository、Shell/HTTP/Native approval、Checkpoint resume。SubAgentParentApproval等。既定設定/一回承認を維持、今回変更不要 |
| Waiting/Follow-up | IMPLEMENTED | PersistentDependencyRepository/ObservationService/Awaiter、DependencyController、Native人間回答、Scheduler。DependencyAwaiterTest/DependencyHttpTest。今回変更不要（新predicate/GitHubは別） |
| GitHub Event Trigger | MISSING | P4参照 |
| Cross-project Today | PARTIAL | P5参照 |
| External implementation | PARTIAL | P6参照 |

## 実装順と設計上の注意

原則は依頼のPhase順を維持する。Phase 1では共通Run/Session/イベントのidentityを保持し、
READをモデルの自己申告だけで並行化しない。Tool実行境界でWRITE拒否が保証されてから
同Project並行を許可する。既存共有Session memoryの更新順序も検証する。
TaskはRun/Goal/子/Scheduler/Dependency/Checkpointへのprojectionとし、Google Tasksと混同しない。
Artifactは専用IDと保存referenceを使い、任意path取得endpointを導入しない。

新機能ごとに独立branch、Red/Green/関連回帰/必要な全体回帰/docs/commit/push/merge/merge後回帰を記録する。
未検証のcodeをmainへmergeしない。外部書込・有料liveモデルの実行を今回の依頼だけで許可としない。

## 今回の検証とGit

JDK25は `C:\Java\jdk-25` に存在するがPATH未登録。
workspaceのMaven cache指定ではAccessDeniedのためテストは開始しなかった。
既存ユーザーcacheを使う実行へ切り替え、通常profileの2 suites / 6 testsが成功した
（failure/error/skipped各0）。integration指定の2クラスは通常profileでは対象外なので、
`-Pfull` で4クラスの再確認も成功した。件数は下記receiptへ記録する。
過去reportの3329件を今回の実行件数へ転記しない。

監査branch: `codex/remaining-features-audit`。feature/merge commitは未作成。
DB migration/API/configの追加はまだない。production変更と各Phaseの完了はまだない。
