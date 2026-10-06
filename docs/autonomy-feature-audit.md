# 自律機能の実装状況（初回2026-10-04、更新2026-10-06）

初回調査の基準: main `b37ac23`。コード・既存テスト・docs・merge 履歴を照合し、以下のfollow-upでMerge済み変更を反映した。
「部分実装」は関連部品が存在することを意味し、候補全体の完成を意味しない。

| Priority | Feature | Initial status | Final status / 根拠 |
|---|---|---|---|
| A1 | Auto Sleep | Not implemented | 本作業で実装。SleepService、AutoSleepService。起動時保存metadataからのbounded候補発見・idle時継続走査に対応。任意cron/日次・zone指定に対応。稼働中の永続metadata定期再検出・100件ページ継続・障害backoffにも対応（auto-sleep-metadata-refresh.md）。明示Session終了／gracefulアプリ終了の永続要求・次回idle処理・Project限定確認とrevision取消にも対応（auto-sleep-end-requests.md）。hard kill時の終了hookは保証対象外 |
| A2 | Autonomous Planning Loop | Partially implemented | 最大16ファイルGoal、全件SHA-256一致。永続 lifecycle/試行/予算、明示起動、既存 Chat/ActionPlan/FIFO、独立検証とbounded継続。不確定Run手動reconcile・予算保持・in-flight除外、SubAgentの通常Chat局所Run・Goal共有LLM予算継承、子cycle/修復/意味検証/並列の同一回数制限、Skill selectorの実呼出直前予算・枯渇/取消伝播、HTTP/実Run/SSE・Native Goal/Checkpoint復旧に対応。親Chat/子/意味検証/Skill/plannerの報告済みRun合計token上限・未知usage停止に加え、新規Goalの上限を作成時に保存する複数Run跨ぎ永続報告token上限・再開/再起動時保持・未報告予約の不明状態保持、Run内context要約のtoken計上に対応。Sleepの1回の抽出・意味解決を共有する呼出回数/報告token予算にも対応（sleep-model-budget.md）。旧consolidate/summarizeにも1操作の共有呼出回数／報告token上限・未知usage停止・保存前停止を追加（memory-consolidation-budget.md）。Sleep跨ぎ・プロジェクト共有の永続呼出回数／報告token予算、失敗・previewの非返金、未報告予約・再起動保持にも対応（sleep-persistent-budget.md）。RunContextなしのcontext組立て内で履歴／実行ログ要約を共有する呼出回数／報告token上限にも対応（standalone-context-summary-budget.md）。外部Codex CLIもopt-inで親Run/Goal回数・報告token予算へ接続し、未確認usage停止・保存前計上に対応（codex-run-model-budget.md）。embeddingもopt-inでTool／Skill／workerの親Run/Goal共有回数・報告tokenへ接続し、probe・未知停止・保存前計上・fallback抑止に対応（embedding-run-model-budget.md）。rerankもopt-inで親Run/Goal予算・報告usage計上・未知停止・Semantic fallback抑止に対応（rerank-run-model-budget.md）。既存SHA条件に加え、最大16の固定JSON Pointer型付きscalar全一致・strict UTF-8/JSON・64KiB・SQLite再起動・Planning/Reflection/Native表示も追加（goal-json-scalar-criteria.md）。任意code／外部汎用条件は追加候補 |
| A3 | Trigger / Scheduler | Partially implemented | 一回限り continuation、SQLite永続化、2〜100回のbounded間隔（1分〜366日）/cron（明示zone、分単位）、明示activation、Project/Session固定、atomic claim、既存FIFO・結果履歴。不確定Run手動reconcile・元予約非再実行・in-flight除外、Run/永続dependency終端event trigger・期限・重複抑制・cursor復元に対応。認証済HTTP/実Run/SSE・Native予約確認/有効化/取消/照合/追跡も対応済み |
| A4 | Waiting / Dependency | Partially implemented | Implemented in this task。状態6種、プロセス/ファイル存在・SHA・変更/Git branch・HEAD/HTTP status/人間回答の条件をSQLite永続化。最大16の同一Session既存依存によるDAG、期限、CAS、キャンセル、復元、opt-in/Policy制御、bounded wait、停滞との区別、Shell・Event API・Scheduler接続を実装。再起動後に不明なprocessはBLOCKED。HTTP_BODY_SHA256でstatus＋完全本文digest一致（最大64KiB/2秒/no redirect）・旧port未対応拒否・NETWORK_READ境界・永続監査へ対応（implementation-report-http-body-dependency.md）。HTTP_JSON_VALUEで固定JSON Pointerの型付きscalar一致・BigDecimal精度・strict JSON/64KiB/2秒・既存network境界も対応（implementation-report-http-json-dependency.md）。任意code/正規表現/複合predicate等は追加候補 |
| A5 | Policy / Permission | Partially implemented | Implemented in this task。能力7種・Chat/SubAgent 境界に加え、永続一回限り承認、Shell/Web の明示決定、Checkpoint の明示 Resume に対応。NativeのProject限定承認一覧・引数確認・一回承認/拒否ボタンに対応。SubAgentの明示設定による読み取り系・同一Project/root/source・親Sessionの正確な一回承認継承と親所有の確認要求に対応。書込権限複製・停止子の自動再開は対象外 |
| A6 | Notification / Escalation | Partially implemented | Implemented in this task（アプリ内確認待ち）。承認要求/Policy拒否/停滞停止/確認済み長時間待機/Run完了・失敗/依存完了・失敗/人間回答待ちの永続 inbox、重複抑制、Shell/Web の表示・ack。依存IDはRunIDと区別し、ackに同意・起動効果なし。Native InboxのProject限定表示・明示ack・所有者切替に対応。既定無効の単一webhook・Project/Policy gate・metadata限定・永続outbox・結果不明非自動再送・明示risk再送も対応（attention-webhook-delivery.md）。provider固有adapterは追加候補 |
| A7 | Reflection / 自己評価 | Partially implemented | Goalの独立検証に加え、Task完了・失敗／Run終端と実Tool結果の構造化自己評価をSQLite保存。期待値・差・観測失敗種別・次回改善／再利用候補、所有者境界、重複抑制、記録上限と不完全性、Shell確認・保存Eventから手動backfillに対応。追加LLMなし。明示Goal Reflection昇格で、owner／snapshot／現在条件を独立再検証した過去のPROJECT_STATEのみ、専用proof・atomic一意保存・忘却非復活として長期記憶へ追加（verified-reflection-memory.md）。自由文の意味的比較／一般化した教訓は追加候補 |
| B8 | Activity weekly/monthly | Partially implemented | 週次/月次の観測・未観測・分類/Project候補・時間帯・日別・前期間差に対応。保存したユーザー分類基準の0〜100適合指数・日別推移・前期間ポイント差、同一Project/分類の25分以上連続観測候補・近接切替候補を追加。未知・空白・系列変更を区別し、推定と成果/集中の実測を分離。既存期間集計/Coaching基準を再利用。Native週次／月次の明示読取り画面・認証済HTTP・bounded保存観測・scope／途中期間表示・接続先／期間変更の遅延応答隔離も対応（native-activity-period-analysis.md） |
| B9 | Adaptive Coaching | Partially implemented | ユーザー分類比率基準・観測品質gate・完了期間限定、永続opt-in設定/期間重複抑制/共通cooldown、Shellに加え、設定で明示有効化した週月自動通知を追加。最大2評価/1通知・単一worker/queueなし、Activity pause/Chat/文脈/設定変更を抑制。既存Activity通知・所有者/会話/Native表示を再利用し、追加LLMなし。意味的個人化/専用分析UIは追加候補 |
| B10 | Activity × Work Context | Partially implemented | 保存Event exact joinに加え、opt-inで観測時の選択Project・Git・現在Work Context revision/ItemとTOOL由来のファイル/command ID/Session/Run参照を標本JSONへ保存。foregroundと出典時刻をShell表示し、旧記録/Project切替/上限/履歴欠落を区別。command本文は複製しない。意味的task帰属/専用UIは追加候補 |
| B11 | Hybrid RAG | Partially implemented | Implemented in this task（opt-in dense/lexical/RRF）。独立候補port、bounded RRF、source/docId境界、既存文書集約/rerank再利用とreranker port、legacy既定維持。元から汎用HTTP rerankは存在。FTS5/BM25は後続で実装（明示opt-in・transaction同期・再構築・RRF統合）。学習sparse・実データ評価は Deferred |
| B12 | Semantic Skill Search | Partially implemented | Implemented (opt-in slice)。 name/description/keywords の embedding、keywordとのRRF、既存rerankerを候補絞り込みに統合。既定無効、明示指定優先、障害時keyword fallback。明示opt-inのSQLite metadata vector永続snapshot・profile SHA/モデルnamespace・再起動cache・現在Skill返却・変更/削除掃除・破損/DB障害/次元変更時の回復にも対応（persistent-skill-index.md）。学習/実データ品質評価はDeferred |
| B13 | Document RAG | Already implemented | Already implemented。VectorDocumentService、SqliteVectorStore、SearchKnowledgeService、CHAT の retrieval が存在。二重実装しない |
| C14 | SubAgent semantic validation | Not implemented | Implemented (evidence-contract slice)。opt-inで実際のTool応答・Run内ID・ハッシュ・引用・SUCCESS required Toolを独立照合。requiredToolCallsで特定JSON引数の実施と引用を要求。任意expectedOutputで同じ実応答の指定フィールドを照合し、失敗結果・解析不能・切り詰めのままSUCCESSにできない。既存bounded修復へ接続。opt-in独立・ToolなしPromptで自由文の根拠なし主張/矛盾/未達を評価し、共有step/Goal予算/timeout・上限付き修復を継承。判定品質評価・別モデル合意は追加候補 |
| C15 | SubAgent repair/retry | Not implemented | Implemented (opt-in validation repair)。最大3回・共有maxSteps・単一timeout・cancel・元の診断履歴保持。モデルtransient障害の応答受信前限定retry（既定0・最大3・cycle/repair/judge共有・予算/未知usage停止・timeout/取消・固定履歴）にも対応（subagent-transient-model-retry.md）。Tool障害retry・永続resumeはDeferred |
| C16 | Parallel SubAgent Delegation | Not implemented | Implemented (bounded independent batch)。最大8依頼/worker2/受付1/120秒、事前検査・入力順結果・部分失敗・親cancel・既存Runner再利用。RunContext継承時の共有Run/Goal呼出回数・報告token上限に対応。親Runなしの直接実行／並列バッチにもopt-in共有回数・報告token予算、修復／judge継承・未知停止を追加（standalone-subagent-budget.md）。DAG/合意形成/永続復旧はDeferred |
| C17 | External Delegation Phase 2+ | Partially implemented | SQLite結果/前回参照・明示新規再レビュー、opt-in CLI UUID保存/明示resumeに加え、Codexの単一ファイル修正案→共通Change Set保存→明示Apply→別Run再レビューを追加。Project/root/対象境界・当該Run認可・共通予算・read-only隔離・原子的一回claim・結果不明非再実行を維持。CLI直接書込/複数agent・並列は追加候補 |
| C18 | Repository Map | Partially implemented | Implemented (bounded Git/Java AST slice)。package/型/method/import/main、module path・テスト名候補、内容ハッシュ更新、Project境界、読み取りTool。多言語/意味的依存解決/永続索引はDeferred |
| C19 | Change/Test Impact | Partially implemented | Implemented (Java structural candidate slice)。Repository Map再利用、逆import/テスト名候補の推移探索・根拠/不完全性・READ Tool。省略引数でGitステージ済み・未ステージ・未追跡の変更pathをbounded収集し既存評価へ接続。削除/rename/除外/不完全を明示（implementation-report-git-change-test-impact.md）。coverage/完全意味解析はDeferred |
| C20 | Build/Test Failure Diagnosis | Partially implemented | Implemented (deterministic process-log slice)。既存command/snapshotにreported test/cause/source・分類・固定確認手順、認証情報除去・上限・context圧縮保持。保存済み単一JUnit XMLのREAD Tool診断・報告countと実case観測の分離・SHA/時刻・不完全性・XML外部参照禁止・上限に対応（implementation-report-junit-report-diagnosis.md）。標準Maven/Gradle配置のbounded自動発見・明示directory・複数XML集合診断・long件数合計・個別SHA/時刻・未読/重複/不完全性の分離にも対応（junit-report-discovery.md）。完全原因特定/その他report形式・自動repairはDeferred |
| C21 | Self Patch Review Loop | Partially implemented | 初回test→同一patch/静的diff review→保存Change Setの実Fix→全サイクル再検証を追加。最大3修正/4round・180秒共有・元の失敗保持・結果不明/進展なし停止。明示testと保存修正案を用いる経路は対応済み。意味review/全Goal強制統合は追加候補 |
| D | Diagram edit / Change Set / Document Agent / Paper E2E | Deferred | 単一既存UTF-8ファイルのSQLite Change Set・完全baseline・差分確認・明示Apply/Discardに対応。document-editor定義/専用Schemaで文書・Mermaid/PlantUMLの自然言語編集案→保存差分→明示適用へ接続。PaperはProvider→条件検査→SQLite/Session/Library→要約/根拠→再起動cacheのオフライン結合を追加し、条件外応答の取込みを修正。複数ファイル/binary文書/専用renderer/live provider E2Eは追加候補 |

## 重複実装を避ける項目

Work Context は `084647a`（PR #31）、Persistent Checkpoint/Resume は `b37ac23`（PR #35）で merge 済み。
対応コードは workcontext/ と checkpoint/、対応テストと実装レポートもある。
Computer Use、SubAgent schema validation、reviewer、OPML、Feed update、Briefing、Reminder、Interest、
Shell Session、path completion、startup project、intervention、compression、Paper Library、Activity 日次、
Agent Event/UI projection、Topic、Core/UI 分離も既存の各パッケージ・テスト・文書を確認した。

## 優先順位と残作業

Auto Sleep、共通 Policy/永続承認、管理プロセス Waiting、永続一回限り・間隔・cron・Run終端イベント Scheduler、アプリ内 escalation は独立実装済み。
ファイルGoalの永続 lifecycle と bounded Planning Loop、Goal事実ベースのReflection、Activity週月の保存観測分析、手動・明示有効化した自動Coachingの期間分析統合、Activity/Work Context保存Event参照、opt-in文書Hybrid RRF、Semantic Skill Searchも実装済み。残件は本表のDeferred項目を優先度順に再評価する。
不確定 Scheduled Run の復旧、永続依存監視、Native の承認・確認待ち・人間回答・Goal/Scheduler/Checkpoint復旧UIは下記follow-upで統合済み。
週月分析は既存の観測事実を集計し、推測や未観測時間を分離する。SubAgent 修復は bounded retry と元 error 保持を先に整備する。

本表の Deferred は実装完了を意味しない。実装を行った機能の検証・Git 結果は別途実装レポートへ記録する。

## Native Checkpoint recovery follow-up

NativeのProject限定Checkpoint一覧・明示照合・差分/結果不明操作/阻害要因確認・再開/放棄確認・既存Runの読み取り追跡を追加。再開Runは保存Sessionを保持し既存実行一覧/SSEへ統合し、結果不明時にresume POSTを自動再送しない。A2/A5のCheckpoint画面部分を対応済みとし、Goal/Scheduler復旧・依存への人間回答UIおよび他のDeferred項目は引き続き未完了。詳細と検証はimplementation-report-native-checkpoint-recovery.mdを参照。

## Human dependency HTTP answer follow-up

A4/A6の人間回答待ちにProject限定の一覧・詳細・保存履歴・明示回答HTTP APIを追加。必須expectedVersionとSQLiteの原子的比較により古い確認/再送で回答を上書きしない。回答は保存された事実であり承認・実行・Run再開・依存完了を直接行わず、既存の監視と前提条件判定を維持する。Native回答画面およびGoal/Scheduler API/UIは引き続き未対応。検証はimplementation-report-dependency-human-controls.mdを参照。

## Native human answers follow-up

A4/A6のNative人間回答待ちUIを復旧・再開画面へ追加。質問・依存/Session・状態・期限・前提条件・保存済み回答を表示し、回答文とversionを確認した後だけ保存する。所有者切替/遅延応答/重複クリックを隔離し、通信失敗後は自動再送せず一覧を更新して再確認する。Tool承認・Run再開・依存完了を直接行わない。Goal/Scheduler復旧およびその他のDeferred項目は引き続き未完了。詳細はimplementation-report-native-human-answers.md。

## Goal HTTP controls follow-up

A2の既存GoalRepository/GoalLoopServiceへProject限定HTTP一覧・詳細・履歴/Attempt・明示Verify/Run/Cancel/Reconcileを追加。GETは保存状態を読み、Verifyは完了状態を更新し得るためPOSTにする。Reconcileは実Run IDと結果不明副作用の明示確認を要求し、queued/executingや古いRunを拒否、予算を回復せずPAUSEDへ移す。Native画面と汎用Run状態/SSE追跡の統合、Scheduler API/UI等は引き続き未対応。検証はimplementation-report-goal-http-controls.mdを参照。

## Goal Run lifecycle follow-up

A2の実Goal AttemptをWeb RunRegistry/RunServiceへ接続し、受付QUEUED・実行・終端・通常のRun取消を既存管理へ統合。保存Session/Projectを保持し、未実行の取消はGoalをPAUSED、AttemptをCANCELLEDへ移す。実行開始直前の取消競合でも処理を実行せず一度だけ通知し、通知はbus monitor外で行う。再起動した不明Runを自動再登録/再実行しない。Native Goal画面、Scheduler API/UI等は引き続き未対応。詳細はimplementation-report-goal-run-lifecycle.md。

## Native Goal controls follow-up

A2の保存GoalにProject限定のNative一覧・状態/履歴確認・実行/Verify/Cancel/Reconcileの明示確認画面を追加。結果不明副作用の確認チェックと実Run IDを要求し、既存予算/Sessionを保持する。受け付けた現在RunをGETで検証し実行一覧/SSEへ接続、同じRunを重複登録せず、追跡失敗時に実行POSTを再送しない。Checkpointと既存追跡経路を共有し回帰検証した。Goal作成は既存入口を使用。Scheduler API/UIおよびその他のDeferred項目は引き続き未完了。詳細はimplementation-report-native-goal-controls.md。

## Scheduler HTTP controls follow-up

A3の保存Schedulerに認証済みProject限定HTTP一覧・詳細・履歴・明示Activate/Cancel/Reconcileを追加。詳細はinterval/cron/event情報を含み、GETは実行や有効化を行わない。既存opt-in dispatch gateと未claim取消規則を維持し、不確定Run照合は実Run IDと副作用確認を要求する。既存dispatcherのin-flight判定と原子的照合を再利用してFAILEDへ移し、再実行・成功の捏造・予算復元を行わない。SchedulerのRun管理/SSEとNative画面およびその他のDeferred項目は引き続き未完了。詳細はimplementation-report-scheduler-http-controls.md。

## Scheduler Run lifecycle follow-up

A3の実Scheduler RunをWeb RunRegistry/RunServiceへ接続し、保存Project/Session・実Run IDのQUEUED/RUNNING/終端を共通状態とイベントへ統合。通常Run取消は未実行の保存claimをCANCELLEDへ移し、dispatcherを解放する。ワーカー開始前の取消でもChatを実行せず、取消Runを成功へ変えない。既存失敗/取消時のrepeat停止・opt-in・FIFO・不確定Runの明示照合を維持し、再起動時に不明Runを再登録/再実行しない。Native Scheduler復旧画面とその他のDeferred項目は引き続き未完了。詳細はimplementation-report-scheduler-run-lifecycle.md。

## Repository summary follow-up

C18の不足していたrepository summaryを既存Repository Mapへ追加。検索/表示limitに依存せず、走査済み全体のmodule/package・Java解析成否・inventory-only・入口・import/テスト名候補件数を集計する。既存のProject境界・秘密除外・内容更新を共有し、要約出力上限や解析不足をsummary.partialで明示する。構造上の観測事実を返し、目的や意味的依存を推測しない。完全意味解析/多言語ASTや他の未完了項目は引き続き残る。詳細はimplementation-report-repository-summary.md。

## Change impact integration/regression follow-up

C19の不足していたaffected module・integration test候補・regression risk根拠を既存Change/Test Impactへ追加。全構造探索のmoduleと、配置/名前/テストcontext importから候補を返し、根拠を明記する。build設定・索引外変更・モジュール間参照・不完全出力では広い回帰検証を要求する。情報が少ない場合も低リスク/coverage保証/テスト省略可とは判定しない。独立出力上限とpartialを付け、実テスト実行や意味的依存の捏造を行わない。詳細はimplementation-report-change-regression-assessment.md。他の未完了項目は引き続き残る。

## Native Scheduler controls follow-up

A3のNative復旧画面へ保存予約のProject限定一覧・日時/action/Session・interval/cron/event・履歴と、明示Activate/Cancel/Reconcileを追加。照合は実Run IDと副作用確認を要求し、再実行しない。保存予約と実RunのProject/SessionをGETで検証して共通実行一覧/SSEへ接続し、重複projectionや実行POSTの再送を行わない。所有者変更/遅延応答/処理中の重複操作を隔離する。Scheduler HTTP・実Run管理・Native復旧の一連を対応済みとし、他の未完了項目は引き続き残る。詳細はimplementation-report-native-schedule-controls.md。

## Activity observation context follow-up

B10の不足していた観測時文脈保存を追加。既存Activityのopt-in観測にGit・選択Project・Work Context revisionと最大20 Item/各8 TOOL出典を保存し、foreground・ファイル・コマンド参照・Session/Runを同じ標本から読めるようにした。保存文脈は後からWork Context履歴が欠落しても維持し、Project変更/未来情報/旧JSON/出力上限と不完全性を区別する。本文を複製せず、ユーザーのtask従事や成果を推測しない。意味的判定等の残件は未完了。詳細はimplementation-report-activity-observation-context.md。

## Text Change Set foundation follow-up

C17/C21の修正実行に必要な共通基盤として、DのAI Change Set / Diff / Applyを単一ファイルから追加。exact UTF-8 baseline・Project/root・保存提案hash・原子的claimを確認して明示Applyし、不確定状態は再実行しない。既存Policy、編集Event、cache更新を維持し、追加LLMは呼ばない。既存非空テキストの提案からreceiptまでを対応済みとし、意味的review・外部fix/continuation・複数ファイルtransaction等は未完了。詳細はtext-change-set.mdとimplementation-report-text-change-set.md。

## Self patch repair follow-up

C21の不足していた実Fixと回数制御を保存Change Setへ接続。まず既存検証サイクルを実施し、明確な失敗とpatch一致時だけ明示修正案を一回Apply、実内容/新patch確認後に全サイクルを再実行する。最大3案/4round、共有180秒を維持し、元の診断・全round・receiptを保持する。不完全/古いpatch/結果不明/変化なしを成功とせず、追加LLMや権限拡張を行わない。意味的レビューや他の未完了項目は残る。詳細はimplementation-report-self-patch-repair.md。
