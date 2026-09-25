# Behavior Notification の会話履歴投影

## 保存するもの

今後 emitted される Behavior Notification の確定文面を、一度だけ生成した同じ AgentMessage から保存する。
NOTICE / WARNING / STRONG_WARNING はすべて対象。NONE、cooldown 等による suppressed、
手動評価、内部診断、生成失敗で未通知のものは保存しない。既存の Behavior Timeline は継続する。

| 会話フィールド | 値 |
| --- | --- |
| speaker / LLM role | assistant |
| source | BEHAVIOR_NOTIFICATION |
| sourceId | AgentMessage.id と EMITTED BehaviorTimelineEvent.id に共通の UUID |
| content | 表示した通知本文そのもの（内部タグを付けない） |
| timestamp / createdAt | AgentMessage.createdAt（通知時刻、保存時刻ではない） |
| metadata | severity、triggerType のみ |

スクリーンショット、raw evidence、Vision response、評価窓の内部情報は会話 metadata にコピーしない。
ログには成功を DEBUG、失敗を WARN で通知 ID とともに記録し、本文や例外の private payload を複製しない。

## パイプラインと責務

既存 BehaviorService は評価 → policy/cooldown 予約永続化 → 一度の文面生成 → 再検証 →
EventAgentMessagePublisher.publish → Behavior Timeline の順に動く。この評価・閾値・頻度は変更しない。
EMITTED Timeline に通知と同じ ID を渡す。

EventAgentMessagePublisher は既存 message.started/delta/completed を一組だけ出力し、
BehaviorConversationHistoryAppender に同じ AgentMessage を渡す。履歴追加による再描画イベントは出さない。
Narrator も一度だけ呼ぶ。履歴保存失敗は捕捉し、既存通知と narration を止めない。
イベント出力が例外になった場合は通常の Behavior FAILED 扱いで、未完了通知を履歴に追加しない。
音声読み上げだけが失敗しても、すでに出力した文章と履歴は維持する。

履歴には元から二つの保存経路がある。

- ConversationLogStore の日別 JSONL: 本文を含む完全履歴と ContextHistoryAdvisor の主な入力。
- ConversationTurnStore の Session Turn JSON と FileSessionRepository の sessions.json:
  Session API、Native Client、現在の /history show が参照する投影。

Appender はまず JSONL を保存し、同じデータから Session と assistant-only Turn を保存する。
会話 Turn は userMessage=""、assistantMessage=本文、turnId=runId="behavior:"+sourceId。
これは履歴上の安定 ID であり、新たな Agent Run を起動・登録するものではない。
通常 Turn の応答と run ID の関係は変更しない。Behavior Timeline は分析・診断用で本文を持たず、
会話履歴はユーザーとの会話・LLM context 用であり、互いを代替しない。

## Project / Session の所有権

Shell の寿命に合わせて ProjectService.notificationsFollow(client) を登録する。
scheduler の ThreadLocal に依存せず、通知時点の Shell の選択 Project / Session を取得する。
公開・履歴保存中は既存 Shell admission と同じ client monitor を保持するため、
次の入力や Project 切替が保存に割り込まない。通知先は immutable な Destination に固定する。

- active Session があれば、その Session を使う。
- active Session がなければ、選択 Project の既存 default conversation chat:main を使う。
  通常 Session metadata を登録して Shell の current Session に選択し、次の user 入力も継続する。
  Behavior 専用 Session は新設しない。初回通知から作る default Session の title はその本文の先頭80 code point。
- Shell のない Web/headless 実行では、最後に受理された会話を current とする。
  未受理なら既存 startup Project の chat:main。Web に「全クライアント共通の表示中 Session」は存在しないため、
  クライアント画面を推測せず admission を基準にする。ブラウザが選択しただけの Session は追跡しない。
- 通知後の Project 切替・再処理でも保存先を移動しない。
- headless では SessionRepository monitor と admission を共有する。
  再起動直後、まだ会話を受理していない場合は startup default へ戻る。

## 重複、順序、障害

idempotency key は source=BEHAVIOR_NOTIFICATION と sourceId。
JSONL の永続レコードを全 Project と legacy global から確認するため、再起動や Project 切替後も同じ ID は増えない。
同文でも別 ID は別発言として保存する。既存レコードがある再処理は表示・音声を再発火せず、
欠けている Session / Turn 投影だけを同じ内容と時刻で補修する。

既存 ConversationLogStore の writeLock によって確認と append を直列化し、通常発言と共通の sequence を採番する。
LLM context は sequence 順。Session API は既存の createdAt → runId 順（同時刻にも安定した順序）。
通知の時刻を順序調整のために書き換えない。

本機能は既存の単一プロセス書き込みモデルを前提とする。複数 rei プロセスによる同じ data-dir への
同時書き込みを排他するものではない。ID 照合は履歴ファイルを走査するため、非常に大きな履歴では将来 index 化を検討する。
壊れた JSONL や読み取り失敗を「ID がない」と見なさず、履歴投影を失敗させる（通知出力は継続）。

UI/event dispatch、JSONL、Session JSON、Behavior SQLite は分散トランザクションにしない。
表示後・JSONL 保存前の異常終了／保存失敗では履歴が欠け得る。
JSONL 保存後の Session 投影失敗は同じ ID の再処理で補修可能だが、自動再試行や起動時 backfill は追加しない。
端末・リモート利用者が実際に目視したことまでは確認できず、既存 publisher の message.* 完了を emitted 境界とする。

## Context、表示、互換性

ContextHistoryAdvisor は通知を通常 assistant として取り込み、source/sourceId と allowlist metadata を保持する。
通常 user → assistant → Behavior assistant → 次の user の順序が維持される。
legacy Turn fallback でも空 user を作らず、source metadata を保持する。
/summarize と既存 compression は同じ履歴を扱い、元 JSONL / Turn は書き換えない。
rolling summary、tool result compression の方式や専用 Behavior 圧縮ポリシーは変更しない。

/history に Session として現れ、/history show は Rei 本文と source/severity を表示する。
通知だけの Turn に空 User 欄を表示しない。legacy log 表示も選択 Session を参照できる。
Web Session Turn DTO は nullable source/sourceId と metadata を追加する。
Native の HTTP → domain → UI DTO → TypeScript へ metadata を通し、assistant-only Turn を通常 Rei 発言として復元する。
今回、新しい専用通知 UI や Native のリアルタイム global 通知購読は追加しない。

通常履歴の古い JSON は source/sourceId=null、metadata={} として読み込み、手動 DB migration は不要。
Behavior SQLite の schema は変更しない。過去 Behavior Event の会話一括移行は行わない。

Topic Generator、Activity detector、Behavior Evaluator を履歴保存から呼ばない。
user activity / chat completion イベントも発生させない。
既存 publisher の recordAgentCompleted のみ維持し、lastUserActivityAt は更新しない。
既存の自動発話抑制・ユーザー活動判定を変えない。

## 主な変更クラス

BehaviorService、AgentMessage、EventAgentMessagePublisher、
BehaviorConversationHistoryAppender、ConversationLogEntry、ConversationLogStore、
ConversationTurnStore、SessionTurn、ProjectService、SessionLifecycle、
SessionHistoryConfiguration、ContextHistoryAdvisor、HistoryCommand、
HistoryShellService、SessionTurnResponse。Shell 起動の ReiApplication に通知 client binding を追加。
Native history model / DTO と Chat の復元表示を更新。

## 検証記録

作業元: main / 0f67897。専用 branch: feature/behavior-conversation-history。
専用 worktree: F:/project/rei-behavior-conversation-history。
元の F:/project/rei は編集せず、merge / cherry-pick しない。

TDD:
1. 同じ通知を二度 publish して assistant 履歴一件・発生時刻を期待するテストを先行追加。
   Red: 実際は二件。target/behavior-history-red.log。
2. ID 付き投影を実装し、このテストを Green にした。
3. Project/Session、復元、失敗隔離、Context、API の統合テストを追加。
   空 User 欄の表示で Red を確認し、Shell 表示を修正。
   compression テストの参照先は既存 log 専用 summary key に合わせて修正。
4. Native DTO の旧 schema と新通知 source の互換テスト、React 表示テストを追加。
5. 評価から生成・表示・Timeline・会話への共通 ID と一度の生成を結合テストで確認。

実 LLM / 実 Activity capture による自然発火は行わず、fixture で通知 → Shell history →
Project 切替 → 元へ戻る → history → 次回 context を確認する。
テスト結果の集計はこの節の追記を参照。


### 最終結果（2026-09-25）

追加: Java 15 ケース、Client 1 ケース、Rust 2 ケース（合計18ケース）。

| 検証 | 結果 | ログ（worktree 内、非コミット） |
| --- | --- | --- |
| Java 全体 | 2353 件中2351成功、既存2件失敗、error/skipなし | target/behavior-history-java-final.log |
| Client 全体 | 42 / 42 成功 | target/behavior-history-client.log |
| TypeScript / Vite build | 成功 | target/behavior-history-client-build.log |
| Rust 全体 | 66 / 66 成功 | target/behavior-history-rust.log |
| E2E（desktop/mobile） | 12 / 12 成功 | target/behavior-history-e2e.log |
| git diff --check | 成功 | — |

Java の失敗は WebBoundaryTest の次の2件。SSE listener 数の期待値との差であり、
変更前 HEAD=0f67897 を専用 worktree の target/behavior-baseline-source に展開して
WebBoundaryTest を実行し、同じ2件の失敗を確認した（target/behavior-history-baseline-web.log）。
元の作業ツリーではテストやファイル変更を行っていない。

- heartbeatHasNoSequenceAndDisconnectReleasesListenerAndTimer
- sendIOExceptionUnsubscribesAndDoesNotCancelRun

今回の通知・履歴・Activity/Behavior・context compression のテストに失敗はない。
全体初回実行で発見した Web 最小構成の ProjectService 依存は ObjectProvider で任意連携に修正し、
WebApiIntegrationTest も再実行で成功した。API fixture の不正 Project UUID も修正済み。

実行には JDK 25、Maven offline と元リポジトリの読み取り専用依存 cache、専用 worktree 内の npm ci / Cargo build を使用。
主なコマンド: mvnw -o -Dmaven.repo.local=F:/project/rei/.m2/repository test、npm test、npm run build、
cargo test、npm run test:e2e。REI_DATA_DIR は worktree の target 内に隔離した。

残課題は既存 WebBoundaryTest 2件、実 LLM・自然発火の実機確認、および上記の非トランザクション障害窓。
コミット ID と最終 git status は作業完了メッセージに記載する。
