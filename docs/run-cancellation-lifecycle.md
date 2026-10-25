# Run キャンセルのライフサイクル修正

## 調査対象と根拠

初期調査は `3ab311ad`、最終統合前の再調査は `974e16d4` を基準とした。作業中の main 更新は専用ブランチに取り込んだ。

提示された一覧と `/cancel` の結果だけでは、発生当時のスレッドがどの処理で止まっていたかは特定できない。本修正では、最新コードから同じ症状を作る複数の経路を自動テストで再現した。単に台帳が二つ存在することを原因とせず、登録・公開・解除の順序と実体の終了条件を調査した。

旧コードの行番号は `974e16d4` に対するもの。

| 原因 | コード上の根拠 | 再現条件・影響 |
| --- | --- | --- |
| 一覧公開が停止登録に先行する | `ConversationInputRouter.java:401` は予約時に active に公開。`ChatExecutionService.java:517` の begin は実行スレッドに入ってから | executor の開始前、admission/presentation の準備中に一覧には存在するが project cancel が見つけない |
| 内側の実行が親の停止情報を置き換える | `CommandCancellationService.java:54-61` は同じ Run ID でも State を新規生成して put | 同一 Run の begin が重なると停止要求、親スレッド、購読、子の停止フックが失われる |
| 実体の cleanup より先に停止登録を消す | `CommandCancellationService.java:184-194` は remove 後に子フックと dispose を実行。`ChatExecutionService.java:495` の clear は Router の forget より前 | cleanup/dispose が長時間戻らないと、一覧は RUNNING のまま project cancel は対象なしになる |
| 一つの停止フックの例外で伝播が途切れる | `CommandCancellationService.java:138` の forEach が無保護 | 後続の子、購読、スレッドへ停止が伝わらない。重複要求でもフックを再実行していた |
| 受付を終了と見なす | `RunService.java:149-154` は停止信号より前に CANCELLED へ遷移 | 実行スレッド、ツール、finally が残っていても API は終了済みになる |
| 購読の破棄と実際の子処理終了が異なる | Chat の latch 待機は解除できるが、scheduler 上のアドバイザー・ツールが割り込みを無視して残り得る | 早過ぎる一覧削除、排他枠解放、終端通知を招く |
| 前面プロセスの待機割り込みに cleanup がない | `Tools.java:857-905` の waitFor と join | 待機側だけが停止し、所有する子プロセスが残る。join は割り込みで解除できない |
| 停止通知が共有ロック内にある | `RunService.cancel()` は bus monitor 内で cancelRun を呼ぶ | dispose/子フックがブロックすると別 Run のイベントや照会も待たされる |

`AgentRunScope` は実行所有者の lexical adapter として保持した。Project/Session の切り替えから実行中 Run の所有権を取り直さない。`RunExecutionContext` の checkActive、Planning Loop の予算確認、Waiting/Dependency の既存の割り込み・停止チェックを再利用する。

## Phase 0: Red の確認

実 LLM を使わず、Fake executor、Reactor の制御可能な stream、Mock Process、Latch と Barrier を使用した。

- `CancellationLifecycleTest`: 二重 begin による要求消失、内側 clear による登録消失、子フック例外の伝播停止が修正前に失敗。
- `RouterCancellationLifecycleTest`: 一覧公開直後の停止、A だけ停止して B を継続するケースが修正前に失敗。
- `CancellationCompletionTest`: 割り込みを無視して残る runner が受付直後に CANCELLED になるケースが修正前に失敗。
- `ForegroundProcessCancellationTest`: waitFor の割り込み後に所有ツリーへ終了信号が送られないケースが修正前に失敗。
- `CleanupCancellationTest`: dispose が戻らない間に一覧から消えるケースが失敗。
- `CancelCommandStateTest` と `CancellationApiDiagnosticsTest`: Run ID 指定と追加の停止進行情報が未提供であることを確認。
- `CancelledRunBoundaryTest`: 子処理が残る間の終端イベントを検出して失敗。
- `CancellationCompletionTest`: 停止フックが共有 bus monitor 内で実行されることを検出して失敗。
- `AdmissionTerminalOwnershipTest`: SQLite admission の直接イベント購読が cleanup 中に COMPLETED を返すことを修正前に検出。通常完了と停止要求の双方を検証する。

Red/Green の実行ログは開発用 `target/cancellation-evidence/` に保存。初回はローカル Maven mirror の名前解決、Mockito の自己接続、一時 Git registry の権限による環境問題も発生した。既存のキャッシュを offline で使用し、Mockito を明示的な Java agent として指定し、実プロセス系は通常実行環境で再検証した。環境による失敗を機能の Red として数えていない。

## Phase 1: 登録と終了の所有者

Router は一覧公開より前に prepare し、実行開始時に thread を結び付け、実際の runner と資源解放が戻った後に finish する。通常チャット、継続入力、application operation が同じ境界を通る。

停止サービスは Run ID ごとに停止信号と所有資源を管理する。Project ID は該当する実行中 Run を選ぶ条件であり、将来の別 Run への停止予約にはしない。開始前の Run ID 停止だけを pending として保持し、開始・破棄・終了と同期する。既知の終了済み ID に未来の停止要求を残さない。

begin/clear が内側で呼ばれても、Router 管理中の State を差し替えたり解除したりしない。直接呼び出す既存コマンドは nesting depth で対を管理する。Agent の停止フラグは終了まで sticky とし、legacy 単独コマンドの consume API は互換動作を残す。

## Phase 2: 停止の伝播と終了確認

停止要求の確定と、割り込み・子フック・dispose の実行を分離した。Run API は共有 monitor の外で停止信号を実行する。各フックは一度だけ実行し、一つの RuntimeException によってほかの停止を妨げない。

実際の非同期子処理には所有権を持たせ、処理本体の finally で解放する。購読の dispose や Future の cancel だけを実体の終了とは見なさない。停止後の子登録を拒否し、既存の checkActive により後続ツール・LLM 呼び出し・再試行を防ぐ。割り込みは cleanup のために一時退避する場合も復元する。キャンセル済み Run から自動 Work Context / Sleep の新規処理を開始しない。

Run API の終端状態と Chat の終端通知は、runner と所有する子処理・cleanup が戻ってから確定する。ProjectRunQueue の既存の排他制約は保持し、停止要求だけで実行枠を解放しない。

前面プロセスは既存 BackgroundProcessManager の通常終了→猶予→強制終了という方針を再利用する。その呼び出しが所有する Process と取得できる descendants だけを対象にする。出力待機を get にして割り込み可能にし、AUTO でまだ background へ引き渡していない待機中プロセスも中断時に片付ける。明示的な BACKGROUND と、既に引き渡した AUTO の独立したプロセス寿命は維持する。

## Phase 3: 利用者向けの状態と互換性

- `/cancel`: 選択中 Project の実行を停止。新規受付と既要求・対象なしを区別する。
- `/cancel RUN_ID`: 特定の Run だけを選び、対象なし・既終了・既要求・新規受付を区別する。
- `/runs`: Run ID と RUNNING / CANCEL_REQUESTED / CANCEL_STALLED / CANCEL_FAILED を表示する。
- Run API: 従来の RunStatus の enum と既存項目を保持し、`cancellation` 診断フィールドを追加する。
- Project cancel API: `accepted` を保持し、`status` と Run ごとの診断一覧 `runs` を追加する。
- Lightweight CLI: Project 単位停止でもレスポンスを表示し、Run の照会・一覧から同じ進行状況を読める。

30 秒以上戻らない要求は CANCEL_STALLED として観測できる。この時間は診断閾値であり、強制終了・登録解除・正常終了の期限ではない。停止信号の失敗は例外の型名を保持する。Run ID、Project ID、要求時刻、終了時刻と結果をログに記録し、例外メッセージ・認証情報・プロンプト本文は追加ログに含めない。

API の構造と既存の Java コンストラクターは保持する。ただし停止受付直後に status=CANCELLED としていた誤ったタイミングは修正した。実行中は status=RUNNING と cancellation.status=CANCEL_REQUESTED を返す。HTTP 202 は受付を示す。実際の終端を polling / event で確認すること。未開始のキュー項目は実体がないため即時 CANCELLED にできる。永続スキーマや RunStatus 自体の追加はない。

## Phase 4: 回帰と制約

追加テストは同一 Project の複数 READ_ONLY Run、別 Project との並列、開始前停止、登録との競合、子フック登録との競合、終了と再要求の競合、重複停止、cleanup 中の表示、停止不能処理、Clock を使った停滞診断を検証する。競合テストは Thread.sleep に依存しない。

初回全件検証は 6,395 件（既存の条件による skip 5）。検出した停止タイミング・承認継続・テスト fixture・キュー受付競合・背景画像保存の回帰を修正した。`0b558040` のローカル再検証は 6,397 件、失敗・エラー 0、skip 5、14 分 20 秒で成功した。Lightweight CLI / launcher は 135 件が成功した。

その後の追加再現で、永続 admission のイベント購読による早過ぎる終端適用を発見した。RunService の実行区間では RunRegistry を終端確定の所有者にし、cleanup 後にだけ永続台帳へ適用する。Web の並列テストも終端イベントと cleanup 完了を区別して検証する。最終コミットは対象回帰テストと静的解析をローカル実行し、全件検証は PR の GitHub Actions で同じ最終 HEAD に対して確認する。

停止は協調的であり、外部サービスが受信済みの処理を取り消せることや、OS から切り離され所有関係を取得できない daemon の停止までは保証しない。無関係なプロセスには信号を送らない。確認できない実体が残る場合は停止待ちとして保持し、診断可能にする。終了済み ID のローカル診断履歴は直近 256 件に制限し、永続的な最終結果は従来の RunRegistry に残す。旧生存プロセスへの hot patch や自動停止は行わず、新しいバイナリで適用する。

## 品質ゲートの個別レビュー

アーキテクチャ予算は一括再生成せず、必要な完全修飾クラス対だけを追加した。C-business の 18 対は Router と停止サービスのライフサイクル接続、非同期 ChildLease、診断値、終端イベントの保持に必要。C-wiring の 4 対と E の 13 対（重複計測を含む）は Run API / CLI の診断値と SubAgent の所有権接続による。新しい UI 逆依存や Configuration の包括的な例外は追加しない。ゲートと guard fixture は変更しない。

承認根拠は今回の依頼で明示された Router / Registry の活用、Run ライフサイクルの一貫性、後方互換な診断フィールド追加。長期移行先は既存ロードマップの Run lifecycle application 層集約であり、今回無関係な全体移設はしない。

SpotBugs の既存 RuntimeException 指摘は、executeInScope の引数追加と Router の synthetic lambda 番号変更に伴う fingerprint 変更だけを 3+1+1 件の同数で移行した。元レポートと新レポートのメソッド・throw 箇所を照合し、新しい例外や detector の抑制は追加していない。

新規 EI_EXPOSE_REP2 の 1 件は Router への共有 CommandCancellationService 注入を個別レビューした。同じ Run を表示と停止で扱うために singleton を共有する必要があり、防御コピーで別サービスにすることは修正目的に反する。Router はサービス参照や内部 State を外へ返さない。これ以外の新規指摘（null 安全性、DTO の可変一覧、boxing、冗長条件、wildcard import）は修正し、許容件数の水増しや包括的な suppression は行わない。

全件検証で追加確認した回帰として、キュー削除が admission を先に CANCELLED にする競合でも初回受付を true にすること、ExecutionScope 内の画像結果保存が ambient フラグではなく明示 Run ID の停止要求を確認することを修正した。承認継続の台帳には公開前の RunStatus を推測させず、既存シグネチャを残した結果付き terminal overload で結果を渡す。背景処理用 scope が親 Agent の begin/clear の対を増減させないことも保証する。


## 最終コミットのローカル検証

- admission / Run API / 承認継続 / Slack / background / architecture の関連 64 件: 失敗・エラー 0。
- Checkstyle: 0 件。SpotBugs: 既存 1,894 件に一致、新規 0 件。
- static-analysis gate の破損・欠落・指摘増加を拒否する fixture も成功。
- 最終 HEAD の全件結果とマージ結果は PR のチェック・履歴を参照。
