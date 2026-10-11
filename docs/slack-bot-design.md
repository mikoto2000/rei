# Slack Phase 1 調査・設計記録

調査対象の main: `cb19ede4`。

## Bluesky の現状

`BlueskyPostTools` / `BlueskyPostService` / `DefaultBlueskyApiClient` が投稿を担当する。
`BlueskyReplyScheduler` は設定されたユーザーの author feed を定期取得し、
`BlueskyReplyService` がメンション、投稿時刻、返信済み、確率、日次上限を判定する。
受信 Webhook や Socket、外部イベントの durable inbox はない。
`BlueskyReplyConversationRepository` のユーザー別履歴を
`BlueskyReplyTextGenerator` に渡し、ツールを無効化した LLM で短文を生成する。
この経路は SessionLifecycle / RunService / Planning Loop を通らない。
SQLite の claim は投稿重複を防ぎ、結果不明の例外では claim を解放しない。
ただし claim 前に生成・履歴追加が行われるため、この順序を Slack にコピーしない。

## 再利用・共通化の判断

再利用するのは既存の SessionRepository / SessionLifecycle、ProjectRegistry、
ConversationInputRouter / ProjectRunQueue、RunRegistry / RunService、
ChatExecutionService、Policy / ToolApprovalRepository、チェックポイントの再開、
AgentEventBus、CredentialRedactor、および SQLite 永続化パターン。
Bluesky の投稿 claim の「結果不明なら再送しない」という原則も採用する。

Slack 固有の ID 認可、Socket Mode、イベント受付、スレッド対応、Outbox、
mrkdwn 変換、レート制限処理を Slack パッケージ内に閉じ込める。
Bluesky のユーザー別履歴、AT Protocol の返信 root/CID、確率的自動返信、
ツール無効生成は共通化しない。両チャネルの意味が異なり、既存動作への影響が大きい。
新しい LLM 実行基盤や汎用 Adapter フレームワークは追加しない。

## 接続経路と注意点

登録済み Project ID を解決し、スレッドごとの Session を永続化する。
Run は既存の入力 Router と RunService を通して実行する。
Project の Shell 選択を変更する API は呼ばない。
既存の ProjectRunQueue は同 Project の排他的 Run を直列化し、別 Project は並行実行する。
既存の承認はツール実行前に exact-input grant を検査し、拒否イベントを発行する。
承認操作自体は既存の Shell / 認証された HTTP API に限定する。
既存の RunStatus は WAITING_APPROVAL を持たず、承認要求とチェックポイントは別管理である。
存在しない Run 状態を前提にせず、Slack の表示状態で区別する必要がある。

受信は永続化後 ACK、実行は非同期。イベント ID と投稿 ID の両方で重複を抑える。
実行前に Run ID を記録し、再起動時の不確かな dispatch は自動で再実行しない。
未処理の受付は復旧対象とし、確実な送信失敗のみ再試行する。
外部投稿が成功したか分からない場合は保留し、運用者が確認する。

回帰リスクは Session の通知選択 callback、Run のキャンセル要求と停止の時間差、
非同期イベントと Run 対応の保存順序、起動条件、SQLite の transaction 境界にある。
Bluesky / Session / Run / Policy / Shell の既存テストを最終確認対象にする。

## SDK と依存関係

Socket Mode は `slack-api-client:1.51.0` の JavaWebSocket backend と
`Java-WebSocket:1.6.0` を使用する。Bolt の別実行基盤は導入しない。
既存依存解決後の OkHttp 4.12.0、Gson 2.13.2、SLF4J 2.0.18 と合わせて
コンパイル・Fake MethodsClient による送信契約を検証する。
SDK の生メッセージ listener が呼ばれる順序を確認し、永続化完了後にのみ
アダプターが ACK を送信する。SDK の envelope listener による別 ACK 経路は登録しない。

## TDD の履歴

受信許可、SQLite の重複排除／Outbox、Session／Run 接続、起動・終了、SDK 境界の順に
小さなテストを追加し、Red／Green の単位でコミットした。
追加レビューで、通常起動の nested configuration、完了 Turn による最終返信、
外部設定テンプレートの網羅性、既存スレッドへの明示メンションをテスト対象にした。
実 Slack の認証情報は使用せず、実環境 E2E は未実施である。
