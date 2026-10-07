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

Artifact Git receipt: feature `76a27198`、main merge `12a7dbc6`、両方push済み。
統合後mainはJava関連14 suites / 45 tests、Native全体119 tests、React25 suites / 99 testsと
型検査成功。failure/error/skipped各0。隔離worktreeは統合時clean、元checkoutの変更は保持した。
Phase 1のP1/P2/P3は今回の実装範囲でIMPLEMENTED。P4以降と外部環境による未検証は残る。

## Phase 2: GitHub Event Triggerの実装

branch `codex/github-event-trigger`、基準はfetch後の `12a7dbc6`。
既存Scheduler event trigger、Dependency、Notification InboxへのGitHub adapterを実装する。
署名済みpayloadから固定の事実だけを抽出し、Agentの指示として本文やコメントを渡さない。
GitHub公式のsignature/delivery/payload仕様を確認した。

HMAC検証前にJSONを読まず、duplicate key/depth/size/time/header confusionを拒否する。
raw本文やコメントを保存・Agent instructionに変換しない。署名済み固定factsだけをProject/root/Sessionへ
mappingし、既存のreview済みScheduler triggerへ渡す。receipt/fact/trigger/Inbox/通知発行待ちは
SQLite transactionで同時保存し、途中のInbox失敗を注入して全体rollbackを確認した。
delivery/body digestの永続重複排除は再起動後も保持し、容量超過は拒否する。
任意の読み取り専用StateVerifierは要求時に未設定/不明/否定を拒否し、既処理再送は再確認しない。

Review通知、CI失敗のみのtrigger、特定PR merge Dependency、TaskのGitHub fact参照を接続した。
通知発行待ちは固定event IDで再送し、既存の許可制metadata配送へ接続した。
通知確認は実行許可・再開を行わない。Native HTTP fixtureで同一のInbox/Task表現を検証し、
Chrome desktop/mobileでGitHub通知とTaskの参照表示を確認した。

Native全体120 tests、React25 suites / 99 testsとbuild、Chrome全体38 testsが成功。
Java全体とGit receiptは完了後に記録する。実GitHub、外部配送、有料モデルは呼んでいない。
操作・設定・保存上限・再確認境界は [GitHub Event Trigger](github-event-trigger.md) を参照。

最初のJava全体は648 suites / 3533 tests、failure 0/error 1/skipped 0。
HTTP fixture追加時のBean名衝突で起動失敗した。既存BusをPublisherとして公開するよう修正し、
さらに条件付きBean登録が登録順でoutboxを省く不足を発見した。
有効なadapterではoutboxを必須登録し、実HTTP3起動とoutbox Bean存在確認が成功した。
この修正を含むJava全体を再実行する。最初の失敗を完成検証の成功とは扱わない。

修正後のJava全体は648 suites / 3533 tests、failure/error/skipped各0で成功した。
追加レビューで、削除・移動したProjectの旧root通知が後続通知を塞ぐ不足を再現した。
旧root通知は送信済みと区別して保存・抑止し、日時の文字列順も実時刻順へ修正した。
この最後の通知修正はGitHub/Inbox/Scheduler/Taskの関連回帰で確認する。

最後の通知修正後はJava関連7 suites / 32 testsが成功し、failure/error/skipped各0。
Native120 tests、React25 suites / 99 tests、format/build、Chrome38 testsは成功済み。
P4はfixture/local HTTPを含む要求範囲でIMPLEMENTED。実GitHub/API adapterは任意の運用拡張点であり、
外部送信と有料モデルは未実行。P5以降は引き続き実装対象として進める。

GitHub Git receipt: feature `858404aa`、main merge `441256d3`、両方push済み。
統合後はJava関連7 suites / 32 tests、Native全体120 tests、React25 suites / 99 testsとtypecheck成功。
failure/error/skipped各0。mainは元checkoutでmergeし、隔離worktreeをそのmergeへdetachして検証した。
元checkoutのLLM変更2件と追加3件は保持し、隔離worktreeは統合時clean。

## Phase 2: Cross-project Todayの実装

branch `codex/cross-project-today`、fetch後の基準 `441256d3`。
明示allowlistの既存Projectのみを対象に、Task projection、保存Work Context、Goal、
Dependency、Scheduler、Checkpointを読み取り専用で集計する。
LLMを呼ばず、日時と保存状態で分類し、Activity観測を成果や集中の証明にしない。

TDDで不足service/HTTPのRedを確認し、明示allowlist、既存Task keyset pagination、
予定時刻、待機、blocker、current Goal、resumed/stale、保存Next Actionのcertaintyを集計した。
既定OFFのBearer HTTPを公開し、Todayだけの有効化でも既存Run RegistryとShell登録を永続化する。
相談modeは既存設定のまま。期限切れDependency、Goal予算、Checkpoint revision、PENDING scheduleを
集計が変更しないことを確認した。HTTP3起動で既定OFF・認証・外部Project拒否・再起動UNKNOWNを検証した。
Activityはbounded保存参照とOBSERVATION_ONLY、DSTはcalendar境界を使用する。
Project/rootを開始と終了で照合し、件数/参照/収集時間の上限とpartial理由を返す。
操作・保存状態の解釈・境界は [Cross-project Today](cross-project-today.md) を参照。
Java全体とGit receiptは検証後に追記する。

Java全体は650 suites / 3541 tests、failure/error/skipped各0で成功した。
Native全体120 tests、React25 suites / 99 testsとtypecheck成功。
P5は最低公開範囲として認証済みHTTPから利用可能で、決定的な読み取り集計をIMPLEMENTED。
LLM順位付け・有料モデル・外部書込を実行せず、任意sourceの未設定と切捨てをwarningsで区別する。

Today Git receipt: feature `73684285`、main merge `73b7b74a`、両方push済み。
統合後Java関連6 suites / 22 tests、Native全体120 tests、React25 suites / 99 testsとtypecheck成功。
failure/error/skipped各0。元checkoutのLLM変更と追加3件は保持し、隔離worktreeは統合時clean。
Phase 2のP4/P5は今回の要求範囲でIMPLEMENTED。P6以降の実装を続ける。

## Phase 3: Codex implementation delegationの実装

branch `codex/isolated-codex-implementation`、fetch後の基準 `73b7b74a`。
既存External Agent provider境界と親Run/Goal予算、process timeout/cancellationを再利用する。
公式CLI資料とローカルexec --helpの隔離config/schema能力を確認した。
helpはモデル・認証・外部書込を呼ばない。実モデルの成功扱いにはしない。

TDDで変更案・隔離worktree・adapter・command・diff preview・cancellation・merge conflictのRedを確認した。
親側がsnapshotのexact SHAを全件検証して、既存UTF-8ファイルのbounded置換を隔離worktreeへ反映する。
固定の管理者test recipe→既存SelfPatchReview→final testを再利用し、元HEAD・changed-file list・source hashes・
test/static review receipt・full Git diff SHA・生成commitを保存する。親のファイルは明示mergeまで変更しない。
保存結果とredacted diffを再レビューでき、実際のhuman requestの結果ID/hashでのみmergeする。
dirty/stale/不完全/想定外/binary/未解決Git操作を拒否し、競合はCONFLICT、結果不明はUNKNOWNで自動abort/retryしない。
merge claimは一度限り。再起動で保存結果を読むだけでは外部CLI・mergeを起動しない。
source snapshot処理をprovider共通へ抽出し、既存Claudeの読取専用回帰を確認した。
実装は毎回ephemeral、親Run/Goal予算を必須共有し、cancelをGit・CLI・実test subprocessへ伝える。
既定OFF、外部出力からtest/Git commandを選ばず、application commandでのみ実装とmergeを実行する。
実装test recipeは既存任意commandと同じ全能力Policyを維持し、権限を狭く偽装しない。
実Git fixtureで親編集の保持、commit/patch照合、変更後の再レビュー要求、競合保存、一度限りmergeを確認した。
nativeモデルは呼ばず、deterministic adapterとlocal processのみで検証した。
操作・制約は [Isolated external implementation](isolated-external-implementation.md) を参照。
Java全体とGit receiptは検証後に追記する。


P6 Java全体は656 suites / 3555 tests、failure/error/skipped各0で成功。
Native全体120 tests、React25 suites / 99 testsとtypecheck成功。
Codex implementation delegationの最低要件をlocal fixtureと公開application commandでIMPLEMENTED。
有料モデル・CLI認証変更・外部サービス書込は実行していない。

Codex implementation Git receipt: feature `0e258475`、main merge `f4b7e6d2`、両方push済み。
統合後Java関連11 suites / 65 tests、Native全体120 tests、React25 suites / 99 testsとtypecheck成功。
failure/error/skipped各0。元checkoutの既存LLM変更2件と追加3件を保持した。

## Phase 3: Claude resume / Change Set / parallelの実装

branch `codex/claude-agent-extensions`、fetch後の基準 `f4b7e6d2`。
既存provider境界、履歴、親予算、bounded parallel workerと隔離実装を共通利用する。
公式CLI referenceとsession資料でresume、UUID指定、Tool-free JSON、subscription authを確認した。
実CLIのモデルや認証は呼ばず、deterministic fixtureから実装する。

Claude用のprivate cwd/root markerとUUID選択・確認をadapter内に追加し、成功した同一provider/Project/rootの履歴を一度だけ継続する。
修正案は既存Text Change Setへ保存し、並列reviewはCodexと同じbounded pool・親Run/Goal予算を使う。
隔離実装も共通worktree/test/hash/commit/merge機構を使い、Claudeの実装呼出しは常にephemeral。
receiptにproviderを保存し、別provider名でのmergeを拒否する。旧receiptのprovider欠落はCodexとして復元する。
保存結果とdiffは同じhuman ownerのREAD_ONLY Runから読めるが、実装とmergeはEXCLUSIVEを要求する。
各機能は既定OFF。subscription/OAuth、auth status、safe-mode、Tool/MCP無効、snapshot再検証、未知副作用の非再試行を維持する。
DBは既存review historyとcontinuation claimを再利用し、新規tableは追加しない。

TDDでは未追加のadapter設定/API、Claude isolated proposal、READ_ONLY receipt拒否のRedを確認した。
adapter・履歴/claim・Change Set・共有予算・並列item・実Git隔離/merge・command/completionの関連回帰が成功。
操作と設定は[Claude拡張](claude-code-extensions.md)に記載。実モデル、認証変更、実サービス送信は未実行。

Java全体659 suites / 3562 tests、failure/error/skipped各0。Native全体120 tests、
React25 suites / 99 testsとtypecheck成功。Claudeの追加5機能をlocal fixtureとしてIMPLEMENTED。

Claude Git receipt: feature `ac582081`、main merge `4c8cda8d0bb4cd8bb22305998e22c884db4da32a`、両方push済み。
統合後Java関連12 suites / 79 tests、Native120 tests、React25 suites / 99 testsとtypecheck成功、failure/error/skipped各0。

## Phase 3: Durable SubAgent resume

branch `codex/durable-subagent-resume`、fetch済み基準main `4c8cda8d`。
既存SubAgentRunner・RunRegistryの親子Run・共通Run/Goal予算を再利用する。
現状の子Run状態だけでは、再起動後の消費予算・未確定Tool・保存結果照合と明示child resumeが不足している。

子checkpointを既存primary SQLiteの `subagent_checkpoints` に保存し、runnerの前後と共通Tool境界へ接続した。
子IDと原親Run/Project/root/human Session、現在の子Run、definition/schema/Git観測baseline、消費・予約予算、
pending model/Tool、結果hashをversion/revision付きで保持する。既定OFF、親Run/Goal予算必須。
native model呼出し・Toolより先に予約/STARTEDを永続化し、PID/start identity喪失をstartupでUNKNOWNへ照合する。
未確定Toolは明示human確認までresume不可。token上限下の未報告usageは予算を復活させず停止する。
保存結果一覧・取得はREAD、照合はEXCLUSIVEのhuman、再開はID/revisionを含む現在の完全一致した明示依頼を要求する。
再開は保存taskと観測からbounded childを新規実行し、残予算を保持する。モデル履歴・中間Tool結果・承認・思考を自動再生しない。
既存Task Managerの子Runにcheckpoint参照を加え、UNKNOWN状態をRunRegistryとも一致させた。
既存RunServiceのUNKNOWN終了でnullを参照する問題も、この結合Redに基づき修正した。

Red→Greenでowner隔離、restart、runner/schema再生成、cancel/明示resume、未知Tool非再試行、
モデル途中crash、stale revision/duplicate claim、READ_ONLY書込拒否、総保存quota/旧checkpoint保持と公開Tool経路を検証した。
新旧SubAgent・並列batch・Task Managerの関連回帰を実施。設定と操作は[Durable SubAgent](durable-subagents.md)を参照。

Git baselineの共通読取がrepository設定fsmonitorを起動することを実GitのRedで確認し、
`--no-optional-locks -c core.fsmonitor=false` を指定して外部補助プログラムを起動しないようにした。
token usageの0も既存親予算と同じ不明判定とし、crash後のtoken予算復活を拒否する。

Java全体661 suites / 3573 tests、failure/error/skipped各0で成功。
Native全体120 tests、React25 suites / 99 testsとtypecheck成功。
Durable childの最低要件をdeterministic model/SQLite/実Gitと公開ToolでIMPLEMENTED。
DAG/consensusは別機能branchで続ける。実モデル品質を検証済みと読み替えない。

Durable child Git receipt: feature `68271a40`、main merge `bc0a0b470584b21ef9e12d67c755dd57e8eb36f7`、両方push済み。
統合後Java関連25 suites / 269 tests、Native120 tests、React25 suites / 99 testsとtypecheck成功、failure/error/skipped各0。

## Claude native storage boundaryの追加確認

branch `codex/claude-native-storage-boundary`、fetch済み基準main `bc0a0b47`。
DAGに先立ち、native private storageの既存／未作成ディレクトリがjunction等を介してsource rootへ解決される場合を検証する。
snapshot reviewのsource不変境界を先に完成させるための順序変更。

実Windows junctionで2ケースのRedを確認。既存aliasにはUUIDディレクトリ、未作成suffixにはsessionsディレクトリがsource内へ作成された。
既存ancestorを作成前にcanonical解決し、source root内へのaliasを拒否する。private base/cwdも作成時に再照合する。
修正後、実junctionの両ケースはsource不変・CLI呼出し0で成功し、Claude継続/修正案/隔離実装の関連6 suites / 34 testsが成功。
failure/error/skipped各0。小さなJava filesystem境界修正のため関連回帰を実施し、直前の全体661 suites / 3573 testsとは区別する。

Claude storage Git receipt: feature `ec95783f`、main merge `51380754f0321f6bd0697117928cd6e5e8566d53`、両方push済み。
統合後もJava関連6 suites / 34 tests、failure/error/skipped各0で成功。

## Phase 3: Durable child DAG

branch `codex/durable-subagent-dag`、fetch済み基準main `51380754`。
既存child runner/checkpointとbounded parallel workerを再利用し、Waiting Dependencyとは別の実行依存graphを追加する。
cycle/依存結果/部分継続/共有予算/再起動のRedから進める。

DAG実装: 既存SQLite child ledgerへGRAPH/CHILD所属とpending model予約数を追加し、全ノードを単一transactionでadmit。
16 nodes / 2 workers / 120秒以内、cycle/input制限、FAIL_FASTと独立枝の部分継続、SUCCESSだけの依存解放、
fan-inの保存child/run/result hashを実装。共有Run/Goalと元graphの消費予算を再開後も保持する。
新poolやWaiting条件の別実装は追加しない。Tool接続はdelegateTaskGraph/getSubAgentGraph/resumeSubAgentGraph。
既定OFF、EXCLUSIVE human Project/root/session、実ユーザー要求・revision・agent/Git baseline、子所属と結果参照を照合する。
通常child resumeでgraph memberを個別実行できない。UNKNOWN Toolは個別reconcile前に自動実行しない。

TDD: spec欠落、共有予約booleanによる未報告usage消失、DAG service欠落、別graphの計画差し替え、
fail-fastの兄弟取消、wave境界での親取消receipt欠落についてRedを確認し修正した。
SQLite再生成後の期限切れchild再開、元予算保持、部分成功/BLOCKED、fan-in hash、ownership/revision、
容量超過時の全体rollback、既存bounded poolのorder/timeout/cancelもローカルfixtureで確認。
関連Java24 suites / 277 tests、Native120 tests、React25 suites / 99 testsとtypecheck成功、failure/error/skipped各0。
Java全体回帰、Git commit/merge receiptは検証後に追記する。実モデルは呼び出していない。
API/config/DB/security制限はdurable-subagent-dag.mdを参照。

DAG Java全体回帰: 663 suites / 3589 tests、failure/error/skipped各0で成功。
Claude native storage追加修正もこの全体回帰に含む。Native120、React25/99とtypecheck成功。
今回のbounded child DAG要件は実装済み。ConsensusとPriority 8以降は引き続き実装する。

DAG Git receipt: feature `40fb5a45`、main merge `d7d98102f267be4bd85feb213127537adbeefa76`、両方push済み。
統合後Java関連27 suites / 288 tests、Native120 tests、React25 suites / 99 testsとtypecheck成功、failure/error/skipped各0。

## Phase 3: SubAgent Consensus

branch `codex/subagent-consensus`、fetch済み基準main `d7d98102`。
現行の独立semantic validatorは単一回答の検証であり、複数child結果の比較APIではない。
保存childのhash/ownershipから独立回答・evidenceを比較し、不一致・根拠不足を未解決として保持する。
任意judgeも既存runnerと共通予算を使い、多数決やjudgeの回答を正解の証明として扱わない。

Consensus実装: 保存child 2–8件の同一task/独立run ID/current result hashから回答・引用・output hashを比較するread-only APIを追加。
異なる回答は多数側で消さずDISAGREEMENT、evidence contractなし／不足／baseline変更はUNRESOLVED。
一致はAGREEMENT_REPORTEDであってtruthVerifiedは常にfalse。共有output hashとモデル/source相関の注意を保持する。
provenanceはchild/run/agent/result hash/task hash/保存時刻、元回答と引用。入力131072文字/answer4096/evidence64件上限。
任意judgeは既定OFF、EXCLUSIVE human Run、管理者定義のtools空agent、既存child runner/Run・Goal予算/deadline/cancelを再利用する。
judge自身のdurable receiptを保持し、元の比較/未解決状態を上書きしない。新DB/tableは追加しない。
ToolはcompareSubAgentAnswers/judgeSubAgentAnswersとして両ChatClient経路へ接続、比較はREAD。
config/API/security/制限はsubagent-consensus.mdを参照。

TDD: serviceとTool欠落のRed、および実runner結果でevidence項目なしを例外にするRedを確認し、
根拠不足のUNRESOLVEDとして保持するよう修正。多数決で正解扱いしない、根拠の重複、定義変更、
ownership/hash/重複/別task/入力上限、両設定opt-in起動、judge予算実消費/子receipt保存を確認。
起動fixtureのmock beanにもSpring autowireが適用されるため、既存permission/approval依存をfixtureに追加。
関連Java6 suites / 32 tests、Native120 tests、React25 suites / 99 testsとtypecheck成功、failure/error/skipped各0。
Java全体回帰とGit receiptは成功後追記する。fixture評価と実モデルの判定品質は区別し、有料モデルは実行していない。

Consensus Java全体回帰: 665 suites / 3599 tests、failure/error/skipped各0で成功。
Native120、React25/99とtypecheckも成功。今回の保存回答/evidence比較と任意judge接続要件は実装済み。
実モデルの意味判定品質は未検証であり、Priority 20の評価harnessとlive環境の確認を区別して継続する。

Consensus Git receipt: feature `7de1f118`、main merge `176222fea040a5b12a7718a27052a6575d83dae6`、両方push済み。
統合後Java関連26 suites / 287 tests、Native120、React25/99とtypecheck成功、failure/error/skipped各0。
Phase 3のCodex/Claude隔離実装・Durable child/DAG/Consensusの実装を統合済み。

## Phase 4: Declarative completion/dependency predicates

branch `codex/declarative-completion-predicates`、fetch済み基準main `176222fe`。
現行はJSON Pointerの単一scalar等値のみ。共通bounded DSLを既存Goal/Dependency観測へ接続する。
任意コードevalは追加せず、version/schema/size/depth/regex/time制限とmissing観測のUNKNOWNを保持する。

Predicate実装: common DeclarativePredicate version 1へAND/OR/NOT/EQ/NE/LT/LTE/GT/GTE/MATCH/EXISTSを追加。
任意code evalは導入せず、strict schema/duplicate keys/version/型比較を検証する。
DSL4096文字/32 nodes/depth8、body64KiB、pointer256/16segments、regex256/program512、評価250ms上限。
RE2/J 1.8を追加し、backreference/lookaroundとcounted repetitionは拒否。compile展開とbacktrackingの危険を抑える。
missing比較/型不正/無効JSON/deadline/cancelはUNKNOWN、NOT/NEで成功に転じない。EXISTSは有効JSONの存在観測。

既存Goal criteriaへnullable predicate_json columnを追加し、旧SHA/scalar constructors/dataを保持する。
既存bounded file reader、GoalLoop/Reflection/認証HTTP verifyを再利用する。
FILE_JSON_PREDICATEとHTTP_JSON_VALUEの{status,predicate} variantを既存Dependency観測へ接続。
既定OFFのrei.predicates.enabled、network authority、watcher opt-in、Project/root/session境界を維持。
無効時のGoal runはclaim/モデルdispatch前に拒否し、HTTPはrequest前にBLOCKED。
UNKNOWNはDependency BLOCKED、不一致はWAITING、SATISFIEDだけCOMPLETED。private bodyをreceiptへ出さない。

TDD: common DSL欠落、Goal/HTTP接続欠落、file Dependency未定義、無効Predicate Goalのdispatch、EXISTS未定義のRedを確認し修正。
型/精度/OR/NOT/missing/version overflow/duplicate fields/complexity/regex/deadline/UTF-8、SQLite再生成、
local HTTP、実Bearer Goal HTTP verifyとモデルdispatch0、既存Goal/Dependency/設定templateの回帰を確認。
関連Java30 suites / 126 tests、Native120、React25/99とtypecheck成功、failure/error/skipped各0。
Java全体回帰とGit receiptは成功後追記する。API/config/DB/security/制限はdeclarative-predicates.mdを参照。
参照: https://github.com/google/re2j 、 https://github.com/google/re2j/releases/tag/re2j-1.8 。

Predicate Java全体回帰: 667 suites / 3611 tests、failure/error/skipped各0で成功。
Native120、React25/99とtypecheckも成功。今回のbounded宣言的Predicate要件は実装済み。
Reflection意味一般化とPriority 10以降を継続する。

Predicate Git receipt: feature `c2b6f6fa`、main merge `d2595a14c85730e941f13ea93a1f0fa486ff07d6`、両方push済み。
統合後Java関連30 suites / 126 tests、Native120、React25/99とtypecheck成功、failure/error/skipped各0。

## Phase 4: Reflection lesson candidates

branch `codex/reflection-lesson-candidates`、fetch済み基準main `d2595a14`。
既存Run/Goal Reflectionはevent事実と固定knowledge候補、PROJECT_STATEの独立proof付き明示昇格を持つ。
意味的lessonの状態/evidence/counterexample/頻度/freshness/ユーザー修正/忘却を独立に検証し、既存proof昇格を維持する。

Reflection lesson ledgerを実装。Run/Goalの期待と実測をsource snapshotへ結び、FAILURE_PATTERN、
独立Goal verification付きSUCCESSFUL_STRATEGY、人が明示記録するREPEATED_CORRECTIONを扱う。
OBSERVATION/CANDIDATE_LESSON/VALIDATED_LESSON/REJECTEDを分離し、3 distinct origins、30日以内、
完全性、source hash、owner/Project/canonical root/Session、revision、反例レビューを昇格gateへ持たせる。
通常Run完了報告は成功証拠にならない。反例と修正はREJECTEDへ移し、忘却statement tombstoneを永続化する。
忘却は新規生成だけでなく既存の等価候補の昇格も止める。Shellの明示操作のみ、モデルToolなし、LTM自動保存なし。
既存PROJECT_STATE proof付き昇格とarchived memory再生成防止を保持する。

TDD: class欠落、human correction観測欠落、Shell接続欠落、等価候補の忘却漏れとcancel昇格漏れのRedを確認。
関連Java5 suites / 40 tests、failure/error/skipped各0。real SQLite restart、実source event、
一時ファイルの独立Goal verificationと変更拒否、Shell、scope、revision、redaction、忘却を確認。
Native120、React25 suites / 99 testsとtypecheck成功。Java全体回帰を実行中。
意味一般化のstatementは人の提案であり、gate合格は記録scope内のレビュー済みrecommendationを表す。
普遍的な正しさや実モデル意味品質を主張しない。設定・API・制限はreflection-lessons.mdを参照。

Reflection Java全体回帰: 668 suites / 3622 tests、failure/error/skipped各0で成功。
Native120、React25/99とtypecheckも成功。実装・ローカル検証完了、Git統合を実施する。

Reflection Git receipt: feature `061034b8`、main merge `b7a050fa41ce2acb6d55d2807fb1a95fb01fbcb2`、両方push済み。
統合後Java関連5 suites / 40 tests、Native120、React25/99とtypecheck成功、failure/error/skipped各0。

## Phase 4: Slack notification provider

branch `codex/slack-notification-provider`、fetch済み基準main `b7a050fa`。
既存Attention Inbox/outbox、Policy、256 queue/1 in-flight、claim-before-send、UNKNOWN recovery、
最大3 attemptsの明示risk付きretryを再利用する。新しい孤立notification体系は作らない。
Slack chat.postMessage公式仕様のok/channel/ts receiptと429 Retry-Afterをlocal HTTP fixtureで検証する。
参照: https://docs.slack.dev/reference/methods/chat.postMessage/ 、 https://docs.slack.dev/apis/web-api/rate-limits/ 。

NotificationProvider boundary、既存Webhook compatibility adapter、Slack chat.postMessage adapterを実装。
delivery.providerは既定WEBHOOK、Slack/automatic/deliveryは明示opt-in。Project/channel allowlistと既存Policyを必須にする。
credential/endpoint本文を保存せず、account/channel identityのdestination hashで設定変更を検出する。
SlackはHTTP200だけでSENTにせず、ok/channel/tsを検証しprovider receiptを既存outboxへ保存する。
未知error/過大・不正body/5xx/transport timeoutはUNKNOWN、確定rejectはFAILED。
429の受信済みRetry-Afterは過大bodyでも失わず、persistent cooldown（1..3600秒）を適用する。
1秒間隔のclaim reservation、1 in-flight、256 queue、最大3 attempts、明示risk付きretryとrestart UNKNOWNを再利用。
provider内で再送しない。config/provider/credential変更で旧pendingをBLOCKEDにし、明示retryだけ保存先を更新する。
旧DBとconstructor/configを維持し、receipt/provider/retryNotBeforeを追加。任意message/ユーザー本文を送らない。

TDD: adapter欠落、outbox接続欠落、未知error/cancel/provider変更、過大429 bodyのRedを確認。
既存parallel claim回帰でSQLite deferred read/write upgradeのlock問題を検出し、writer取得をread前に修正。
関連Java6 suites / 46 tests、failure/error/skipped各0。real local HTTP/SQLite restart/legacy migration、
receipt/allowlist/redaction/Policy/disable/duplicate/manual retry/429/timeout/redirect/credential rotationを確認。
Native120、React25/99とtypecheck成功。実Slack送信は行っていない。Java全体回帰を実行中。
設定・API・制限はslack-notification-provider.mdを参照。

Slack Java全体回帰: 670 suites / 3636 tests、failure/error/skipped各0で成功。
Native120、React25/99とtypecheck成功。実装・ローカル検証完了、Git統合を実施する。

Slack Git receipt: feature `81413147`、main merge `1a8e27548cd5a96b6e5c7c5b9ff3da187410f68d`、両方push済み。
統合後Java関連6 suites / 46 tests、Native120、React25/99とtypecheck成功、failure/error/skipped各0。

## Phase 5: Multilanguage Repository Map / Impact

branch `codex/multilanguage-repository-map`、fetch済み基準main `1a8e2754`。
既存Java AST parseのみで、TS/JS/Rust/Go/PythonはINVENTORY_ONLY。
同じMapと逆依存Impactへbounded heuristic構造解析を追加し、semantic resolverとの区別と不完全性を明示する。
read-only inventoryのfsmonitor設定をChange Impactと揃え、既存Java persistent metadataとroot境界を維持する。

同じRepositoryMap/ImpactへTS/JS/Rust/Go/Pythonのbounded lexical/line-oriented heuristicを追加。
language/analysisMode、宣言/symbol/function/method/type、package/namespace、build roots、local imports、
HEURISTIC_IMPORT/TEST_NAME_CANDIDATEと逆依存を返す。既存Java ASTと旧fields/constructorsを保持。
heuristicは常にpartialでsemantic resolverではない。曖昧候補は任意の1つへ決めず、Impactは広い回帰を要求する。
source/build/package managerを実行せず、source bodiesは出力/永続化しない。16MiB/128KiB/1024files/10秒等を再利用。
build metadataは64file/16KiB、lexer32Ktokens/32commentdepth/16usedepth。secret-bearing importを秘匿。
Java-only persistent metadataをmixed Projectでも維持し、heuristic sourceは永続化しない。

TDD: language解析欠落、coverage警告、manifest namespace cache、Go module identity/version、
secret import literalとPython build-root越境のRedを確認し修正。
関連Java6 suites / 43 tests、failure/error/skipped各0。全言語fixture、real Git fsmonitor control、
JDK compiler無し、SQLite mixed metadata、曖昧候補、source編集/cache/ownership/credential/boundsを確認。
設定・API・対応範囲はmultilanguage-repository-map.mdを参照。Java全体回帰を実行する。

多言語Map Java全体回帰: 672 suites / 3649 tests、failure/error/skipped各0で成功。
Native120、React25/99とtypecheck成功。実装・ローカル検証完了、Git統合を実施する。

多言語Map Git receipt: feature `9dd80460`、main merge `507445ee15aae543aed16291158cba71150d507a`、両方push済み。
統合後Java関連6 suites / 43 tests、Native120、React25/99とtypecheck成功、failure/error/skipped各0。

## Phase 5: Coverage / Test Impact

branch `codex/coverage-test-impact`、fetch済み基準main `507445ee`。
既存Impactは構造候補のみで、pom/clientにcoverage生成設定はない。JaCoCo/LCOVを中心に保存reportの
行観測を既存Impactへ接続し、coverage不在・未計測・stale/曖昧source・aggregateとtest別を区別する。
公式JaCoCo DTDとLCOV tracefile仕様を確認。外部DTDを取得せず、実テスト実行の証明と混同しない。
参照: https://github.com/jacoco/jacoco/blob/master/org.jacoco.report/src/org/jacoco/report/xml/report.dtd 、
https://github.com/linux-test-project/lcov/blob/master/docs/man/geninfo.rst 。

Coverage TDD: 未実装parser、既存Impactへのoverload/coverage欄、Git/Tool接続の各Redを確認。
JaCoCo/Cobertura/LCOVを安全に解析し、明示line rangeまたはGit HEAD-to-worktreeの行へ観測を接続した。
既存reverse dependencyとtest candidateを維持。報告positive/zero、未計測、stale、no reports、
利用不能/invalid/曖昧sourceを区別。aggregateからcovering testを推測しない。
同一Map snapshotとcurrent source SHA、report SHA/timeを返すがsource revisionとtest成功は未証明なので
coverageは常にpartial、広い回帰を要求。report generationやtest実行はREAD Toolに追加しない。
上限: report8件/各2MiB、range64/合計256line、10秒/10Krows/XMLdepth32等。
外部DTD/XXE/escape/link/secretTN/不完全document/cancelを制御。敏感な変更pathのdiff bodyは取得しない。
関連Java5 suites / 44 tests、failure/error/skipped各0。Native120、React25/99とtypecheck成功。
API例・状態・対応format・限界はcoverage-test-impact.md。Java全体回帰を実行中。

初回全体回帰: 673 suites / 3659 tests、failure/error/skipped各0。
追加監査でGit本文のheader類似文字列によるpath誤対応をRedで再現し、header読み取り境界を修正。
関連3 suites / 24 tests成功（Coverage9・Git9・Impact6）。最終codeの全体回帰を再実行する。
最終Coverage Java全体回帰: 673 suites / 3660 tests、failure/error/skipped各0。Native120、React25/99とtypecheck成功。Git統合を実施する。

Coverage Git receipt: feature `e463d90a`、main merge `17c7258bc3fc308ddddc20e892fa88fbe344eec1`、両方push済み。
統合後Java関連5 suites / 45 tests、Native120、React25/99とtypecheck成功、failure/error/skipped各0。

## Phase 5: Failure Diagnosis → Repair

branch `codex/diagnosis-repair-flow`、fetch済み基準main `17c7258b`。
既存SelfPatchRepairは保存案・完全失敗・再検証・最大3修正/180秒を実装済み。
不足していた失敗report identity→patch→修正Change Setの保存接続と人の明示承認を追加する。

Diagnosis→Repair: 保存JUnitの完全な失敗観測・SHA/time、Git patch version、既存Text Change Setと
明示command/timeoutを同一DataSource transactionで結び、Project/root/session別receipt hashを保存。
propose/inspect/apply Toolと/repair show|apply Shell入口を追加。自動repairは既定OFF。
人の実際の現在requestが正確な/repair apply ID receiptHashと一致する場合だけ適用可能。
既存全能力Policyに加え、Text Change Set保存guardで通常Apply/selfRepairによる迂回を防止。
READ_ONLYは案を参照できるがWRITEを拒否。stale/future/不完全report、変更patch、別session、
使用済み/結果不明案、再観測した失敗identity不一致を拒否。一回CAS claimの後、同じ
SelfPatchRepairで失敗→適用→test→static review→final test。元の失敗と全round/receiptを保存。
共有180秒を診断preflightから渡し、補充しない。1保存修正/2round/4tests、各1..60秒、
patch32files、text64KiB、history128/Project、record128KiB。追加LLM/外部呼出しは0。
STARTED/crash/UNKNOWNや失敗の自動再送なし。成功repeatは歴史receipt読取でwriterを再実行しない。
TDD: 未実装接続、caller deadline、Tool/人入口、READ_ONLY参照、通常Apply迂回のRedを確認。
実Git/実Shell/SQLiteのJUnit失敗更新→承認→Apply→3回command/最終成功、index不変を確認。
関連Java7 suites / 40 tests、failure/error/skipped各0。Native120、React25/99とtypecheck成功。
設定/API/限界はdiagnosed-repair-flow.md。Java全体回帰を実行中。
Diagnosis→Repair Java全体回帰: 674 suites / 3667 tests、failure/error/skipped各0。Native120、React25/99とtypecheck成功。Git統合を実施する。

Diagnosis→Repair Git receipt: feature `20a6c0b0`、main merge `a291c645571b7417d05e834797544907f0b87bf3`、両方push済み。
統合後Java関連7 suites / 40 tests、Native120、React25/99とtypecheck成功、failure/error/skipped各0。
元のLLM関連dirty2件とuntracked3件を保持している。

## Phase 5: Semantic Patch Review

branch `codex/semantic-patch-review`、fetch済み基準main `a291c645`。
既存SelfPatchReviewは同一patch・明示command exit・静的diffのみ。
要件/変更範囲/testcase evidence/current file SHAのdeterministic gateとoptional Tool-free semantic
reviewを同じ検証サイクルへ接続し、保存receiptを次のGoal completion gateへ渡せるようにする。

Semantic Patch Review: 既存test/static/final cycleに要件→変更file→必須JUnit testcaseの
保存接続を追加。追加/未割当変更・必須証拠欠落・skip/古い/不完全report・patch driftは
FIX_REQUIRED。source64KiB/diff32KiB、要件16/files32/reports8/tests64、共有180秒。
コメント/文字列を区別する追加行hygiene、不明diff headerは保守的に確認要求。
Tool入口は既存任意command全能力Policy、receipt読取のみREAD。意味Review既定OFF、
親Run/Goal共有予約1call/30秒/retryなし/Tool-free、全6観点と各要件のstrict verdict。
不正JSON/ambient Tool/予算欠落/cancel、UNKNOWNを肯定へ読み替えない。
SQLite write-ahead unique claim、owner/root/session/SHA、history128/Project/4096全体、
receipt128KiB、同じRun/requestの再送でcommandを繰返さない。raw source/diff非保存。
モデル一致は確率的判断、truthVerified=false、command-report provenanceは保証しない。
TDD未実装Toolとunmapped diffによる偽成功のRed→Greenを確認。
関連5 suites / 43 tests成功。実Git/実Shell/SQLiteでJUnit更新・2回command・index不変・
receipt再送無実行を確認。Java全体回帰中。Native120、React25/99/typecheck成功。
仕様/API/DB/config/限界はsemantic-patch-review.md。
Semantic Patch Review Java全体回帰: 676 suites / 3679 tests、failure/error/skipped各0。Native120、React25/99とtypecheck成功。Git統合を実施する。

Semantic Patch Review Git receipt: feature `28ae998a`、main merge `ca97e6223b238736642923023b0560c426f81859`、両方push済み。
統合後Java関連7 suites / 59 tests、Native120、React25/99とtypecheck成功、failure/error/skipped各0。
広いgit addは自動承認reviewにより拒否。隔離worktreeの今回の16fileの明示stagingで解消。
元のLLM dirty2件とuntracked3件を保持し、今回のcommitには含めていない。

## Phase 5: Goal Completion Gate

branch `codex/goal-completion-gate`、fetch済み基準main `ca97e622`。
既存FileGoalVerifierとGoalLoopの全完了経路を拡張し、保存completion definition/proofを照合する。

Goal completion gate: agent_goalsにnullable completion_json/proof_jsonをmigrationし、人の
completion evidence/required tests/artifacts/predicates/review gateを保存。旧constructor/行を維持。
FileGoalVerifierとGoalLoop/Gatewayの全完了経路に接続。全Goal強制は既定OFF。
人のShell/strict JSON認証HTTP入口、READの定義参照、現在Goal Run限定LOCAL_WRITE証拠添付。
実行中/terminal定義変更拒否、Project/root/session/Run/READ_ONLY制御、定義変更はproofを失効。
既存test/static receiptのowner/24h鮮度/command SHA/必須testcase/report SHA/current patch version、
ArtifactStoreのowner/AVAILABLE/expiry/content hash、既存scalar/declarative predicatesを照合。
追加LLM0、30秒の共有検証予算、各type16件/tests64/JSON32KiB/depth16。
モデル宣言だけで完了しない。不足証拠は既存Run予算内で継続、UNKNOWN/拒否は停止。
完了時は検証したdefinition/proofをCASし、競合した定義で完了することを防ぐ。
Reflection/verified observationもcompletion_gate_verifiedを扱い、現在の再検証を維持。
TDD: 未実装gate/人HTTP入口と定義更新競合のRed→Green。新規Shell fixtureの不正UUIDを修正。
関連Java14 suites / 98 tests、failure/error/skipped各0。実Git/Shell/SQLite Review→Goal完了、
実HTTP認証/不正JSON/実行中定義変更拒否、保存再読取、current Goal Run証拠添付を確認。
Native120、React25/99とtypecheck成功。Java全体回帰中。仕様はgoal-completion-gate.md。
Goal completion gate Java全体回帰: 678 suites / 3691 tests、failure/error/skipped各0。Native120、React25/99とtypecheck成功。Git統合を実施する。

Goal completion gate Git receipt: feature `03abab6c`、main merge `72e355413769f30ae3304d440911ba5f7c1caf48`、両方push済み。
統合後Java関連14 suites / 98 tests、Native120、React25/99とtypecheck成功、failure/error/skipped各0。
元のLLM dirty2件とuntracked3件を保持。

## Phase 6: Multi-file Document Change Set

branch `codex/multi-file-document-change-set`、fetch済み基準main `72e35541`。
既存単一UTF-8 Change Setはexact baseline/保存hash/一回claimを持つ。複数fileの
保存proposal/journal、create/delete/rename、logical apply/rollback/UNKNOWN復旧が不足している。

Multi-file Document Change Set: additive SQLite tableを既存Repositoryへ追加。
UPDATE/CREATE/DELETE/RENAME、per-file/current SHA・whole proposal hash、captured
Project/root/session、EXCLUSIVE/Policy、全baseline再確認、staging+baseline backup、
write-ahead journal・PID/start identity、全target検証後のみAPPLIED・一回event。
通常失敗/cancelは逆順rollback、外部編集は保護しUNKNOWN、hard-stop後自動再送なし。
UNKNOWNは既存single-file Applyも排他。実human requestの明示rollback最大3回と
owned staging cleanup。未適用破棄・安全なterminal履歴100件保持、active/UNKNOWN非削除。
External Agentのprivate worktreeも共通transactionを使用、selected manifestを再確認。
TDD: 未実装multi-file/Tool/External adapter/破棄のRed→Green、実Windows junction、
fatal stop保存再読取、second-publication failure、cancel、外部編集・重複eventを検証。
関連8 suites / 44 tests成功、Native120、React25/99/typecheck成功。Java全体回帰中。
仕様・DB/API/config/physical atomicity限界はmulti-file-document-change-set.md。
Multi-file Document Change Set Java全体回帰: 680 suites / 3706 tests、failure/error/skipped各0。Native120、React25/99/typecheck成功。Git統合を実施する。

Multi-file Document Git receipt: feature `a995653c`、main merge
`209d8759787d8c748158666e4a777b5d8a40bb7a`、両方push済み。
統合後関連8 suites / 44 tests、Native120、React25/99/typecheck成功。
元のdirty2件とuntracked3件を保持。

## Phase 6: Renderer Validation

branch `codex/document-renderer-validation`、fetch済み基準main `209d8759`。
既存document-editor profileがPlantUMLを扱うが実renderer検証・Artifact接続が不足。
Mermaid CLIは利用可能な設定がなく、既存Markdownはtext previewを提供するため、
最初の限定対応はPlantUML PNG。ローカルjar 1.2022.7で実構文エラーexit200、
正常exit0・2424byte PNGとSHAを確認。管理者指定jarとSANDBOXを用いる。
Renderer Validation: 管理者指定canonical jar/JVM、既定OFF、standalone PlantUML PNG。
既存Tools/Policy/ArtifactStoreへ接続。source SHA前後一致、directive/function/resource
markup拒否、SANDBOX/credential環境非継承/private TEMP、20秒process deadline、
stdout4MiB/stderr8KiB、4096px/16Mpixel、exit/parse/full PNG decode/size/SHA確認。
SQLite write-ahead unique Project/root/session/Run/request claimとPID/start保存。
再送は保存receipt参照、lost operation UNKNOWN、time-out/invalid/unavailableは成功なし。
Artifact有効時のみimmutable PNG公開、無効なら明示warningとartifactなし。
TDD未実装API・resource markup・描画中source driftのRed→Green。
実Windowsの環境を絞る際のTEMP不足を実rendererで検出しprivate TEMPで修正。
一回の診断プロセスはstderr回収待ちとなり、確認済み固有PIDのみ終了して回収。
実PlantUML error/PNG→Artifact content、子JVM deadline/cancel停止を確認。
関連7 suites / 42 tests成功、Native120、React25/99/typecheck成功。Java全体回帰中。
仕様/API/DB/config/rendererなし時のskipはdocument-renderer-validation.md。
有料モデル・実Slack・ユーザーsourceへの自動Applyは実行していない。Renderer初回全体回帰: 682 suites / 3716 tests、failure0/error1/skipped0。
ArtifactRecoveryTestのready markerが作成直後・内容書込前に見え、空IDでgetして
ResourceNotFoundになり得た。fixture markerを同directoryのatomic move公開へ変更し、
finallyのprocess終了待機も追加。productionのArtifact判定を弱めず復旧harnessを修正。
全体成功扱いにはせず、関連復旧→全体回帰を再実施する。
Renderer Validation再全体回帰: 682 suites / 3716 tests、failure/error/skipped各0。実PlantUML fixtureを明示指定。Native120、React25/99/typecheck成功。Git統合を実施する。

Renderer Git receipt: feature `c1275077`、main merge
`a487b335815255f1b2abb2aec0402e785363a514`、両方push済み。
統合後関連8 suites / 43 tests、Native120、React25/99/typecheck成功。

## Phase 7: RAG / Skill retrieval quality evaluation

branch `codex/retrieval-quality-evaluation`、fetch済みmain `a487b335`。
既存HybridRetriever/RRF/SemanticSkillSearch/CandidateRerankerを使う評価harnessを追加。
relevance/hard negatives/expected top-kを持つ匿名fixtureと4指標が不足している。
Retrieval quality evaluation: query/graded relevance/hard negatives/expected top-kの匿名JSON。
Recall@k、Precision@k（不足枠も分母k）、MRR@k、graded nDCG@k、expected order一致。
lexical/dense/RRF×rerank OFF/ONの6比較、各case rankingとmacro平均、provider identity。
既存HybridRetriever/CandidateReranker/keyword selector/SemanticSkillSearchを再利用。
Skill instructions非使用、catalog/query metadataのみ。評価adapterで検出したfallbackは
失敗とし、実HTTP RerankServiceも評価専用strict入口を追加。runtimeのfallback維持。
128cases/256candidate/grade1..3/query8192/identity1024/共有30秒、cancel伝播。
fixture指標は実モデル品質ではなくtruthVerified=false、有料モデル呼出0。
TDD未実装指標/実HTTP strict入口とhidden rerank fallbackのRed→Green。
fixture出力directoryのテスト順依存を修正。関連8 suites / 52 tests成功、各0。
出力target/evaluation/rag-quality.json・skill-quality.json、仕様retrieval-quality-evaluation.md。
DB/config/Native/React変更なし。Phase7の評価基盤各機能を関連回帰し、Phase末に全体回帰する。
Retrieval evaluation Git receipt: feature `03b7e4b8`、main merge
`10a2c6db0f6b02737af670977c8be75fa173f8eb`、両方push済み。
統合後関連8 suites / 52 tests、failure/error/skipped各0。

## Phase 7: Learned Sparse provider boundary

branch `codex/learned-sparse-provider-boundary`、fetch済みmain `10a2c6db`。
production/config/resources/pom検索で設定済みSparse/SPLADE/BGE providerと依存なし。
既存SQLite BM25/FTS5・dense/RRFを維持し、optional encoder/index境界のみ整備する。SparseEncoderのimmutable numeric vectorと明示SparseRetrieval encoder/index境界を追加。
model identity/dimensions/filter整合、不正vector/empty/timeout/provider不在は理由付きBM25。
cancel/共有予算停止はfallbackしない。BM25自体の障害は再実行しない。
TDD追加のbaseline失敗試験は二重実行でRed、provider失敗分類とfallback実行を分けGreen。
DB/config/default検索変更なし。provider既定必須化・自動モデル呼出・学習品質保証なし。
関連5 suites / 42 tests成功、failure/error/skipped各0。Phase7末に全体回帰する。
