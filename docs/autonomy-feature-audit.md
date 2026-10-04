# 自律機能の実装状況（2026-10-04）

基準: main `b37ac23`。コード・既存テスト・docs・merge 履歴を照合した。
「部分実装」は関連部品が存在することを意味し、候補全体の完成を意味しない。

| Priority | Feature | Initial status | Final status / 根拠 |
|---|---|---|---|
| A1 | Auto Sleep | Not implemented | 本作業で実装。SleepService、AutoSleepService。起動時保存metadataからのbounded候補発見・idle時継続走査に対応。任意cron/日次・zone指定に対応。終了trigger/外部metadata監視はDeferred |
| A2 | Autonomous Planning Loop | Partially implemented | Implemented in this task（最大16ファイルGoal、全件SHA-256一致）。永続 lifecycle/試行/予算、明示起動、既存 Chat/ActionPlan/FIFO の再利用、独立 SHA-256 判定と bounded 継続。Shellの不確定Run手動reconcile・予算保持・in-flight除外に対応。SubAgentのTool cycle/修復/並列の共有LLM予算継承に対応。汎用検証条件/Skill selector等の予算継承/共通token上限/Native復旧UIはDeferred |
| A3 | Trigger / Scheduler | Partially implemented | Implemented in this task（一回限り continuation）。SQLite 永続化、2〜100回のbounded間隔予約（1分〜366日）・cron予約（明示zone、分単位）、明示 activation、Project/Session 固定、atomic claim、既存 FIFO 経由の bounded dispatch・結果履歴。Shellの不確定Run手動reconcile・元予約非再実行・in-flight除外に対応。対象Run終端イベントの一回限りtrigger、WAITING_EVENT/期限/重複抑制、保存履歴のbounded cursor復元に対応。永続dependency終端イベントからの起動も対応。Native復旧UIはDeferred |
| A4 | Waiting / Dependency | Partially implemented | Implemented in this task。状態6種、プロセス/ファイル存在・SHA・変更/Git branch・HEAD/HTTP status/人間回答の条件をSQLite永続化。最大16の同一Session既存依存によるDAG、期限、CAS、キャンセル、復元、opt-in/Policy制御、bounded wait、停滞との区別、Shell・Event API・Scheduler接続を実装。再起動後に不明なprocessはBLOCKED。HTTP body/任意code predicate等の拡張は対象外の追加候補 |
| A5 | Policy / Permission | Partially implemented | Implemented in this task。能力7種・Chat/SubAgent 境界に加え、永続一回限り承認、Shell/Web の明示決定、Checkpoint の明示 Resume に対応。NativeのProject限定承認一覧・引数確認・一回承認/拒否ボタンに対応。SubAgent 承認継承は Deferred |
| A6 | Notification / Escalation | Partially implemented | Implemented in this task（アプリ内確認待ち）。承認要求/Policy拒否/停滞停止/確認済み長時間待機/Run完了・失敗/依存完了・失敗/人間回答待ちの永続 inbox、重複抑制、Shell/Web の表示・ack。依存IDはRunIDと区別し、ackに同意・起動効果なし。Native InboxのProject限定表示・明示ack・所有者切替に対応。外部配送は Deferred |
| A7 | Reflection / 自己評価 | Partially implemented | Goalの独立検証に加え、Task完了・失敗／Run終端と実Tool結果の構造化自己評価をSQLite保存。期待値・差・観測失敗種別・次回改善／再利用候補、所有者境界、重複抑制、記録上限と不完全性、Shell確認・保存Eventから手動backfillに対応。追加LLMなし。意味的比較／検証済み長期知識への昇格はDeferred |
| B8 | Activity weekly/monthly | Partially implemented | 週次/月次の観測・未観測・分類/Project候補・時間帯・日別・前期間差に対応。保存したユーザー分類基準の0〜100適合指数・日別推移・前期間ポイント差、同一Project/分類の25分以上連続観測候補・近接切替候補を追加。未知・空白・系列変更を区別し、推定と成果/集中の実測を分離。既存期間集計/Coaching基準を再利用。Native専用UIはDeferred |
| B9 | Adaptive Coaching | Partially implemented | Implemented in this task（手動期間分析統合）。ユーザー分類比率基準・観測品質gate・完了期間限定、永続opt-in設定/期間重複抑制/共通cooldown、Shell。既存Behavior通知を維持。自動週月通知/意味的個人化/専用UIは Deferred |
| B10 | Activity × Work Context | Partially implemented | Implemented in this task（保存Event参照）。ActivityのEvent/Session/Turn/Run ID、Work ContextのTOOL出典へのexact project-scoped join、revision/Item/Git snapshot時刻付きShell表示。観測時Git/ファイル/command・意味的task帰属・専用UIは Deferred |
| B11 | Hybrid RAG | Partially implemented | Implemented in this task（opt-in dense/lexical/RRF）。独立候補port、bounded RRF、source/docId境界、既存文書集約/rerank再利用とreranker port、legacy既定維持。元から汎用HTTP rerankは存在。FTS/BM25/学習sparse・評価は Deferred |
| B12 | Semantic Skill Search | Partially implemented | Implemented (opt-in slice)。 name/description/keywords の embedding、keywordとのRRF、既存rerankerを候補絞り込みに統合。既定無効、明示指定優先、障害時keyword fallback。永続index・学習評価はDeferred |
| B13 | Document RAG | Already implemented | Already implemented。VectorDocumentService、SqliteVectorStore、SearchKnowledgeService、CHAT の retrieval が存在。二重実装しない |
| C14 | SubAgent semantic validation | Not implemented | Implemented (evidence-contract slice)。opt-inで実際のTool応答・Run内ID・ハッシュ・引用・SUCCESS required Toolを独立照合。自由文の意味判断/特定引数のrequired taskはDeferred |
| C15 | SubAgent repair/retry | Not implemented | Implemented (opt-in validation repair)。最大3回・共有maxSteps・単一timeout・cancel・元の診断履歴保持。モデル/Tool障害のretry・永続resumeはDeferred |
| C16 | Parallel SubAgent Delegation | Not implemented | Implemented (bounded independent batch)。最大8依頼/worker2/受付1/120秒、事前検査・入力順結果・部分失敗・親cancel・既存Runner再利用。DAG/合意形成/永続復旧/全子token予算はDeferred |
| C17 | External Delegation Phase 2+ | Partially implemented | Implemented (durable review metadata / explicit re-review)。SQLite結果/前回参照、Project/root境界・明示認可・共通予算・結果不明扱い。fix/書込/CLI session resume/複数agentはDeferred |
| C18 | Repository Map | Partially implemented | Implemented (bounded Git/Java AST slice)。package/型/method/import/main、module path・テスト名候補、内容ハッシュ更新、Project境界、読み取りTool。多言語/意味的依存解決/永続索引はDeferred |
| C19 | Change/Test Impact | Partially implemented | Implemented (Java structural candidate slice)。Repository Map再利用、逆import/テスト名候補の推移探索・根拠/不完全性・READ Tool。Git差分自動取得/coverage/完全意味解析はDeferred |
| C20 | Build/Test Failure Diagnosis | Partially implemented | Implemented (deterministic process-log slice)。既存command/snapshotにreported test/cause/source・分類・固定確認手順、認証情報除去・上限・context圧縮保持。完全原因特定/report読取/自動repairはDeferred |
| C21 | Self Patch Review Loop | Partially implemented | Implemented (explicit bounded static verification cycle)。初回test→同一patch確認/静的diff review→最終test、Fix要求・変更/不完全/失敗の完了拒否。意味review/自動fix/全Goal強制統合はDeferred |
| D | Diagram edit / Change Set / Document Agent / Paper E2E | Deferred | Deferred。A〜C の依存基盤と検証を優先 |

## 重複実装を避ける項目

Work Context は `084647a`（PR #31）、Persistent Checkpoint/Resume は `b37ac23`（PR #35）で merge 済み。
対応コードは workcontext/ と checkpoint/、対応テストと実装レポートもある。
Computer Use、SubAgent schema validation、reviewer、OPML、Feed update、Briefing、Reminder、Interest、
Shell Session、path completion、startup project、intervention、compression、Paper Library、Activity 日次、
Agent Event/UI projection、Topic、Core/UI 分離も既存の各パッケージ・テスト・文書を確認した。

## 優先順位と残作業

Auto Sleep、共通 Policy/永続承認、管理プロセス Waiting、永続一回限り・間隔・cron・Run終端イベント Scheduler、アプリ内 escalation は独立実装済み。
ファイルGoalの永続 lifecycle と bounded Planning Loop、Goal事実ベースのReflection、Activity週月の保存観測分析、手動Coachingの期間分析統合、Activity/Work Context保存Event参照、opt-in文書Hybrid RRF、Semantic Skill Searchも実装済み。残件は本表のDeferred項目を優先度順に再評価する。
並行して、不確定 Scheduled Run の復旧、汎用依存監視、Native の承認・確認待ち UI を既存基盤へ統合する。
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
