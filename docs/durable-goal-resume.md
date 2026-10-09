# Goal の永続待機・条件確認・継続（Phase 4）

明示的な Goal に既存 Dependency / Scheduler / Checkpoint を接続する。通常チャットへ待機や Goal を強制しない。待機条件の登録、依存先の人間回答、Scheduler の activation は既存の人間操作を使う。新しい実行エンジン、通知送信機構、承認機構、外部プロセス再起動は追加しない。

## 保存と開始

既存 memoryConsolidationDataSource に agent_goal_waits を追加する。古い Goal、Dependency、Checkpoint のレコードを書き換える移行は不要。Goal / 最後の Run / attempt 数、理由、Dependency / Schedule ID、条件、最後の観測、版数、開始時の完了済み・未達条件、Completion definition、Checkpoint task / revision、操作状態、再開情報を保存する。現在の条件は既存 completion-progress で再検証できる。

停止した READY / PAUSED / FAILED / WAITING_APPROVAL / BLOCKED Goal のみを結び付ける。既に実行した Goal は、その最後の Run と Project / root / Session が一致する Checkpoint が必要。取得できない場合は副作用を確認せず自動継続しない。Goal を PAUSED にし、既存 Goal history と goal.updated の WAITING phase を記録する。同じ Goal の active wait は一つ、同じ Project の active wait は最大256。snapshot は最大128KiB。

待機の作成、Goal の停止状態照合、Scheduler の PENDING 作成、binding 保存は同じ DataSource の transaction 内で行う。作成だけではモデルを実行しない。既存 Scheduler と同様、人間が activation するまでイベントで起動しない。

## Shell / HTTP

作業先の Project と Session を選択して使用する。

- /goal wait GOAL --dependency-id DEPENDENCY --wait-reason test_job_wait
- /goal wait GOAL：保存情報を照会
- /goal wait-activate GOAL --wait-version VERSION：既存 Scheduler を明示activation
- /goal wait-resume GOAL --wait-version VERSION：現在の条件を照合して人間が再開
- /goal wait-cancel GOAL --wait-version VERSION：停止した待機を解除

HTTP の認証済み /api/v1/projects/PROJECT/goals/GOAL/wait に GET / POST。作成bodyは dependencyId / reason。/wait/activate、/wait/resume、/wait/cancel の POST は expectedVersion が必須。版数なし・不正値は400、条件未達・競合は409。通常 /goal run と HTTP run は active wait を迂回できない。待機解除は不明な副作用の確認を意味しない。

Native Client の既存 Dependency 回答、Scheduler activation、Goal/Run 状態確認・取消、Checkpoint の個別確認は維持する。binding の新規作成と版数付き手動 resume は Shell / HTTP を使う。Native に新しい待機作成フォームは追加していない。SSE / Notification は既存 Goal / Dependency / Checkpoint のイベント経路を使う。

## 条件を再検証してから再開

DependencyObservationService.recheckForResume は、過去に COMPLETED となった条件も fresh probe で照合する。保存済み状態の履歴を成功に上書きしない。前提Dependencyも最大64件まで再検証する。取消・失敗・期限切れ・移転した所有者は再開しない。HTTP は既存 SSRF / size / timeout 制限と checkHttpDependency の AUTO_APPROVE が必要。Policy 無効・承認未取得・禁止の場合は観測しない。USER_ANSWER はツール権限の昇格を意味しない。

PROCESS_EXIT では既存管理プロセスの結果照会を行い、テストジョブも同じ成功終了条件を使う。再起動でプロセスの接続情報を失った場合は BLOCKED。FILE_CHANGED、GIT_STATE_CHANGED、HTTP、USER_ANSWER は既存の probe を再利用する。古いファイル・HTTP 条件が元へ戻ったなら再開しない。activation 時点で既に成立した条件には、現在の観測を既存 Scheduler に渡す。

CheckpointReconciler が UNKNOWN / STARTED 操作、照合不能なプロセス、所有者・schema 問題を示す場合は再開しない。既存 checkpoint confirm / resolve で runId:toolCallId 単位の実際の結果を確認する。包括的な「確認した」で全不明操作を成功にしない。

条件成立後、wait を RESUMING に CAS し、同じ transaction の Goal claim で wait 版数・状態、Goal の停止状態・Run・attempt・定義を再照合する。既存 Goal / Run 予算は補充しない。Checkpoint の lease と最新 revision を再確認し、既存 Checkpoint を新しい Goal Run へ引き継ぐ。計画と証拠は ChatExecutionService の通常 start / finish と Resume context で復元する。別 Chat や二重の LLM 呼び出しは起動しない。

Scheduler の COMPLETED は Goal の「再開受付」を示す。Goal 自体の達成は引き続き Completion Gate / Goal 状態で確認する。モデルの自己申告だけで成功にしない。

## 重複・中断・取消

Goal Run ID と実行中 attempt を照合し、古い callback / LLM reservation が次の attempt を完了・失敗・消費できないようにする。同じイベントの重複は既存 Scheduler claim、wait CAS、Goal claim、Checkpoint lease で一回の受付に制限する。すでに開始した外部操作が実際に何回実行されたかまで exactly-once を保証しない。

RESUMING のまま中断した場合、再起動で勝手に再送しない。Goal / Schedule の正確な Run を既存 reconcile で確認し、不明な操作は個別確認する。実行中のものが残らない状態で wait-cancel し、必要なら新しい binding を作る。保存履歴と消費済み予算は残る。古い binding の条件や定義を推測で変更しない。

外部状態の変化とローカル claim を完全に同一 transaction にできない。再開直前の照合は行うが、その直後の外部変化を防ぐ保証ではない。外部更新には対象サービスの idempotency key / 現在状態照会 / 人間確認が必要。プロセス再接続が不明なら再生成せず報告する。

## 有効化と検証

明示Goalを使い、rei.tool-permission.enabled=true。自動イベント継続には rei.agent-scheduler.enabled=true、条件の継続観測には rei.dependency-watcher.enabled=true、既存 Checkpoint は rei.checkpoint.enabled=true（既定）を使う。対象ツールの capability を管理者が設定し、HTTP を使うなら NETWORK_READ の許可が必要。Shell / HTTP の manual resume も fresh observation の AUTO_APPROVE を必要とする。取消・承認・絶対予算を迂回しない。

旧データを読む回帰、実 SQLite 再起動、古い callback / reservation、条件の撤回、前提条件、HTTP permission、6種の待機、Scheduler 統合、個別副作用確認、再接続不能、通知、取消競合、予算、定義変更、認証と版数を検証する。Red では所有権・新APIの compile failure・再接続不能の誤再開・RunのないDependency通知の失敗を再現した。外部モデル・ネットワーク・プロセスはダブル、一時ファイル / SQLite と既存 FIFO / Checkpoint / Scheduler は実装を使う。