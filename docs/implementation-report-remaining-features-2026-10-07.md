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

| 外部確認事項 | 開始時分類 | 未確認理由 |
|---|---|---|
| CLIの実認証・実モデルreview/resume/implementation | BLOCKED_BY_EXTERNAL_ENVIRONMENT | モデル利用許可・accountの確認を行っていない。CLI存在だけで成功にしない |
| Paper実provider E2E | BLOCKED_BY_EXTERNAL_ENVIRONMENT | live providerの有効化/credential/実接続を今回確認していない |
| Slack実配送 | BLOCKED_BY_EXTERNAL_ENVIRONMENT | destination/credential/実送信許可がない。transport実装不足とは分離 |
| 実モデル検索・意味検証品質 | BLOCKED_BY_EXTERNAL_ENVIRONMENT | 有料モデルを実行していない。stub評価pipelineの不足は別に実装対象とする |

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

監査branch: `codex/remaining-features-audit`。feature `8ddfa487`、merge `0ccd6898`、両方push済み。
監査時点ではDB migration/API/configの追加はない。各Phase全体の完了はまだない。

## Phase 1前提: bounded Run admission

独立branch `codex/bounded-run-admission`。既存ProjectRunQueueに同Project64/全体256の
実行中を含む受付上限を追加し、上限到達は専用CapacityExceededExceptionとして同期拒否する。
新しいqueue/frameworkは追加しない。HTTPはこの専用例外だけを429へ変換する。
一般executor障害を容量不足と誤表示しない。ChatSubmitServiceの既存rollbackが拒否Runを削除する。
Project FIFO/他Project並行/個別cancel/cleanup前の書込枠保持を維持する。
この変更でREAD並行化やTask Managerが完成したとは扱わない。

TDD: constructor未実装compile Red → queue Green → HTTP期待429/実際500のbehavior Red
→ 専用例外/HTTP mapping Green。境界値、不正limit、待機取消、完了による枠解放、
executor拒否、32同時受付の全体上限、HTTP拒否後ghost Runなしを検証する。
Run/Session/mailbox/Background operation関連の9クラス回帰は成功。
初回Java全体回帰は3463 tests、failure 15/error 1/skipped 0。
失敗はWindowsの長classpathによる実プロセス起動に集中したため、成功と扱わない。
独立harness修正 `5d5f74ee` / main merge `123ad0f8` を先に統合した。
再起動/外部プロセス/Background Process/診断/長classpathの5 suites・26 testsは成功した。
全ソースのclean compile後の全体回帰は624 suites / 3466 tests、failure/error/skipped各0で成功した。
実行: JDK25、`mvnw.cmd -q -Pfull clean test`。
DB migrationなし、config追加なし、固定上限の利用説明をconfiguration.mdへ追加した。
Native/Reactは変更していない。実サービス・モデルは実行していない。

## 今回ここまでの検証・Git receipt

| 作業 | Feature branch | Feature commit | Main merge commit | Tests |
|---|---|---|---|---|
| 全件開始時監査 | codex/remaining-features-audit | 8ddfa487 | 0ccd6898 | 開始時関連4 suites / 9 tests成功 |
| Windows実JVM起動harness | codex/windows-checkpoint-restart-harness | 5d5f74ee | 123ad0f8 | 5 suites / 26 tests成功、後続全体回帰成功 |
| Run受付上限/HTTP 429 | codex/bounded-run-admission | cfab4654 | 34cf9b61 | 全体624 suites / 3466 tests成功、merge後14 suites / 54 tests成功 |

全体回帰・merge後回帰ともfailure/error/skipped各0。live E2Eは未実施。
Native/Rust・React・typecheckは未実施（この変更はJavaとdocsのみ）。
上記feature/mergeはpush済み。検証用worktreeは34cf9b61時点でcleanを確認した。
元checkoutは開始時のLLM変更と引き継ぎ文書を保持するためdirtyのままであり、cleanと報告しない。

依頼全体は未完了。P1のREAD並行実行・Native入力、P2 Task projection、P3共通Artifactから
順に実装する必要があり、開始時監査表の残りも未完了である。
今回の受付上限とharness修正を全Phase完成へ読み替えない。

## Phase 1: 同時会話Runの実装

独立branch `codex/concurrent-conversation-runs`。既存Run/Session/ProjectRunQueueを拡張し、
既定OFFの `rei.conversation.concurrent-enabled` を追加した。作業(EXCLUSIVE)、
相談(CONVERSATION)、読み取り(READ_ONLY)、Run IDを選んだ追加指示をShell/HTTP/Nativeへ接続した。
READは既知の読み取りToolだけを実行し、権限設定によるWRITEの読み替えを拒否する。
相談はToolなし。共有memory/作業状態を更新せず、完了済み履歴を開始時に固定する。
同ProjectのWRITE排他、待機WRITEの公平性、受付上限、共通予算、個別取消を維持した。
制限付きRunは全実行期限を持ち、期限切れと取消を区別する。

SQLite Run metadataとCheckpoint modeを保存する。PIDと開始時刻による所有権確認、
状態更新CAS、受付失敗rollback、別JVMの強制終了後UNKNOWN復元を確認した。
受付済み処理を自動再実行しない。イベント履歴を失った終端RunもSSE replay gapで状態取得へ戻す。
操作と設定は [同時会話Run](concurrent-conversation-runs.md) を参照。
Taskの長期一覧・Native再起動後の一覧取得はP2 projectionとの統合で完成させる。
この段階でPhase 1全体やP2/P3の完成とは扱わない。

TDDでqueue競合/公平性/取消、権限拒否、個別mailbox、共有状態分離、履歴固定、
期限切れ、永続所有権、HTTP受付/部分失敗/Session終了/再起動、Native/React入力を検証した。
初回Java全体は627 suites / 3489 tests、failure 1/error 8/skipped 0で失敗した。
既定OFF時の不要なDataSource必須化と、待機executor拒否の例外伝播を修正し、
該当7 suitesの回帰を成功させた。実HTTP再起動試験で終端SSE待機も修正した。
2回目Java全体は628 suites / 3490 tests、failure 0/error 1/skipped 0。
終端状態とイベント保存の競合を、再起動由来の履歴消失と区別できていないSSE回帰を修正した。
3回目Java全体は628 suites / 3490 tests、failure/error/skipped各0で成功した。
実行はJDK25、`mvnw.cmd -q -Pfull test`。全体Redを成功扱いせず、修正後の結果を確認した。
Native Rust全体は108 tests成功、React全体は23 suites / 87 tests成功。
React typecheckとformat、Rust formatも成功。有料モデル・外部サービス書き込みは未実施。

同時会話Run Git receipt: feature `d06748eb`、main merge `451f326f`、両方push済み。
統合後mainの関連Javaは18 suites / 82 tests、Native Rust全体108 tests、
React全体23 suites / 87 testsとtypecheckが成功した。failure/error/skipped各0。
隔離worktreeは統合時clean。元checkoutのLLM未コミット変更と引き継ぎ文書は保持した。

## Phase 1: Task projectionの実装

branch `codex/task-manager-projection`。Run/Checkpoint/Goal/Dependency/Scheduler/SubAgentを
既存資料から投影し、登録Projectの横断一覧、所有Session境界、安定cursor、関連参照を追加した。
元のrepositoryへ制御を委譲し、別のTask実行queueは作成しない。
HTTPとNativeにsubmit/get/list/cancel/suspend/resume/inputを接続した。
CheckpointのTask IDはoriginalRunIdに固定し、再開時のrunId変更と区別する。
入力は対象Runの次のiteration、再開は保存予算を復元・補充しない明示操作である。

SubAgentは共通Run Registryに親Run/Sessionを保存する。実際の子実行・個別取消は既存runnerに
接続し、子取消で親を止めない。これはP7の永続子checkpoint/DAG実装完了を意味しない。
Task対応のShell登録は相談modeの有効化とは独立する。

TDDで不足method/HTTP復元/Source一覧切捨て/所有プロセス終了/Goal再開条件のRedを確認し、
対応するGreenと関連回帰を実行した。270 Dependency、270 Goal+270 Scheduler+1005 Checkpointを
ページングするfixture、別JVM hard kill、実HTTP3起動による既定OFF/Bearer/受付/再起動を含む。
Native再起動・HTTP所有境界・操作payload・stale revisionの書込前拒否、React切替競合・
明示再開・拒否操作の非再送・絞込/pagination・新規受付も検証している。

検証とGit receiptは完了後に追記する。共通Artifact参照はP3で統合するため、Phase 1全体は未完了。
操作・保存期間・migration・既知の境界は [Task Manager](task-manager.md) を参照。

最初のJava全体は637 suites / 3511 tests、failure/error/skipped各0。
続くレビューで、実行中Goalの取消・中断可否が共通Runの所有権を反映していない不足を発見した。
別Registry・Run不明のfixture Redを確認し、操作不可とUNKNOWN表示の修正後関連回帰を成功させた。
この修正を含む最終Java全体は637 suites / 3512 tests、failure/error/skipped各0で成功した。
Native Rust全体は114 tests成功、React全体は24 suites / 92 tests成功、typecheck/format/build成功。
Chromeのdesktop/mobileはTask専用2件と既存画面を含む全体30件が成功した。
最初のブラウザー試験は未対応fixtureで失敗し終了処理が停滞したため、今回起動したPIDとコマンドを
照合して終了し、通常のChrome実行権限で再実行した。停滞した試験を成功扱いしない。

Task Git receipt: feature `e05dd8b7`、main merge `5f9ed1a8`、両方push済み。
統合後mainはJava関連24 suites / 122 tests、Native全体114 tests、React24 suites / 92 testsと
typecheck成功。failure/error/skipped各0。統合時の隔離worktreeはclean、元checkoutの変更は保持した。

## Phase 1: Artifact Deliveryの実装

branch `codex/artifact-delivery`。共通SQLite metadataとUUID保存コピー、Bearer APIの
一覧/所有取得/内容/明示削除、Nativeの一覧/preview/明示Downloads保存を追加した。
Project/root/Session、canonical UUID、サイズ、SHA-256を照合する。元pathをAPIへ渡さない。
Run/Task結果からの参照、Image生成、保存済みPaper版と単一ファイル変更提案の明示exportを接続した。
Paper exportはモデルやHTTPを実行せず、提案exportはApplyしない。

TDDで不足store/API/Native command/Task projection/source exportのRedからGreenを確認した。
別JVMを公開予約後とatomic move後で強制終了し、UNKNOWN復元と再生成拒否を検証した。
画像寸法制限、期限切れ、欠落・改変、所有Session拒否、保存先競合、UTF-8 filename、
サーバー切替のstale応答破棄、HTMLのテキスト表示、画像読込失敗を確認した。
Native全体119 tests、React25 suites / 99 tests、format/build/typecheck、
Chrome desktop/mobile全画面36 testsが成功した。画像とテキストのNative取得はローカルHTTP fixture。
操作と保存上限は [Artifact Delivery](artifact-delivery.md) を参照。
Java全体とGit receiptは完了後に記録する。P4以降は未完了であり、全件完了とは扱わない。

最初のJava全体は644 suites / 3523 tests、failure 1/error 0/skipped 0。
新規Artifact enabled設定が外部設定テンプレートにないため網羅性テストが失敗した。
テンプレートを修正し関連回帰を成功させた。追加レビューでは子Runのprivate会話IDと
Taskの所有Sessionが異なる不足をTDDで再現し、Registryの親Sessionを引き継いで修正した。
会話/WorkspaceからのRun生成物リンク、古いpreviewの読込失敗後の消去、Chrome PNG実デコードも検証した。
最初の全体Redを完成検証の成功扱いにせず、修正後のJava全体を再実行している。

修正後のJava全体は644 suites / 3524 tests、failure/error/skipped各0で成功した。
Native全体119 tests、最終React25 suites / 99 testsとformat/build/typecheckが成功。
Chrome desktop/mobile全体36 tests（PNG decode、Runリンク、Task結果、明示保存を含む）が成功。
有料モデル・外部サービスへの書き込みは実行していない。
