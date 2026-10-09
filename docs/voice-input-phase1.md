# 音声入力 Phase 1 — 共通入力基盤

## 実装範囲

Phase 0 の PR #53 が main の `536f20628b443ade2431bba856bb2dc8037cc9c6` に統合された後に開始した。Java 25 を維持する。マイク・JNI・モデルの本番組み込み、音声コマンド、会話スタイルはこの Phase では未実装。

`ConversationInput` は UUID、KEYBOARD/VOICE、捕捉済みProject/Session、text、Instantを持つ。`ConversationTarget` は固定したProjectContextとSession IDであり、音声ワーカーは後からThreadLocalや選択中Sessionを参照しない。`ShellConversationService.captureTarget()` はShellのクライアントロック下で対象を捕捉する。Sessionが未選択なら空のSessionを作成して選択し、Agentは起動しない。

キーボードの `submit(String,Mode)` とモック音声の `submit(ConversationInput)` が `ConversationInputGateway → SessionLifecycle → 既存dispatch → ConversationInputRouter → ProjectRunQueue → ChatExecutionService` を共有する。音声専用ChatClientやCLI疑似入力は追加しない。Native/HTTP経路のSessionLifecycleも維持する。

## 入力・重複・順序

- 入力IDに同じpayloadを再送すると既存RunContextを返し、再enqueueしない。異なるpayloadやModeで同じIDを使うと拒否する。台帳への受付は同期され、並列の重複イベントも1回だけ受け付ける。取消済みIDも重複として保持する。
- 台帳は最大4096件。30分を経過した実行終了済みの記録だけを整理し、実行中・保留中の記録を消さない。上限時は明示的に受付を拒否する。
- 台帳から整理した古い入力を再実行しないため、新規受付はcreatedAtが過去30分以内であることを要求する。継続中のIDの重複照会は期限を超えても既存Contextを返す。台帳はプロセス内のものであり、再起動をまたぐ永続的な重複検出ではない。
- dispatchが同期失敗した入力は台帳に登録せず、同じIDで再試行できる。SessionRepositoryの既存の永続化・rollback契約を維持する。
- 音声はEXCLUSIVEで新規Runへ入り、同一Project/Sessionの通常入力とFIFOを共有する。既存キーボードの明示CONVERSATION/READ_ONLY並列Modeと権限制約は維持する。この実行ModeはPhase7の応答スタイルとは別概念。
- 実行キューの既存上限64/project・256/totalを維持する。さらに音声の実行開始前の保留をShellサービス全体で3件までに制限する。ProjectRunQueueがrunningになるまで、executorへ予約済みの入力も保留として数える。

## 取消・権限・表示

`pending(target)` はVOICE保留を受付順で取得する。`cancelPending(target,inputId)` はProject ID・正規化root・Sessionが一致する入力だけを対象とする。共通取消はKEYBOARDにも使え、VOICE保留一覧とは分離する。Sessionの切替で入力を付け替えない。

RunRegistryを利用している構成では `RunService.cancelQueuedOnly` を通して、キューからの除去とCANCELLED状態・イベントを整合させる。実行開始との競合で取消対象が保留でなくなった場合はfalseを返す。保留取消が実行中Agentの停止へ切り替わることはない。既存の通常Run取消は維持する。

KEYBOARD/VOICEは入力属性であり、AgentRunContext.RequestSourceは既存SHELLのまま。ToolPermissionGuard・Policy・Planning Loop・完了検証を変更しない。音声の空入力、Session未捕捉、先頭空白を除いて `/` で始まる入力は受付前に拒否する。音声はPicocliを通さない。既存JLineShellEventOutputの同期・printAbove・入力途中の表示保護も維持する。

認識テキストは既存会話へ渡るので、後続の実音声受付では通常の会話履歴・既存のLLM送信設定が適用される。このPhaseのテストでは実LLM・録音・モデル取得を行わない。台帳はテキストをメモリ内に保持するが、独自の認識テキストログや録音ファイルは追加しない。

## TDDと検証

ConversationInputGatewayTest、PendingOnlyRunCancellationTestを追加。モデル未実装compile Red、保留限定取消API未実装compile Red、キュー未接続の拒否不足Red、期限切れ入力の再実行Redを確認し、各最小実装を追加した。

検証対象: キーボードとVOICEの共通Runner/Session/権限、Session切替後の固定、slash拒否、3件上限、取消とFIFO、所有境界、同時重複、ID衝突、受付失敗後の再試行、4096件台帳の有限性と実行中保持、古い入力拒否、Tool承認、RunRegistry整合、開始済みRunの保留取消拒否。既存のSession/ChatCommand/Router/ProjectRunQueue/JLine/Policyテストも実行する。

影響範囲50件はfailure0 / error0 / skipped0で成功。その後キーボード保留取消のRedを追加し最小修正したため、最終全Java回帰・CIの結果は確定後に追記する。Phase1はレビュー・テスト・main統合まで未完了で、Phase2へは進まない。
レビューで、取消後の後続executor拒否が取消呼出しへ伝播する既存キューの境界を再現した。Redテストを追加し、後続の拒否callbackが失敗を管理しつつ、除去済み入力の取消はtrueを返すよう最小修正した。旧実装の全回帰はこの修正のため自分のMavenプロセスを停止した（成功扱いしない）。修正後の最終全回帰を再実行する。

最終影響範囲: 52件、failure0 / error0 / skipped0、BUILD SUCCESS。修正後の全Java回帰とPRのCIは未確定。

修正後の最終全Java回帰: 4106件、failure0 / error0 / skipped1（既存PlantUML条件）、BUILD SUCCESS、10分26秒、wrapper終了0。GitHub CI・PRレビュー・main統合は未完了。
