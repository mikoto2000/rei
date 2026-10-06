# Attention webhook配送

既存の永続 Inbox へ、管理者が設定した単一 webhook の配送を追加する。既定無効。モデルが送信先・credential・対象Projectを指定するToolは提供しない。実装検証はローカルHTTP fixtureのみで、実サービスへ通知していない。

## 設定

`rei.attention.delivery.enabled` と `projects`、`endpoint` を明示する。endpointはHTTPS、userinfo/query/fragmentは禁止。数字loopback HTTPはローカルfixture用に許可する。任意の`bearer-token`は送信先にのみ渡し、設定recordのtoStringでは隠す。環境変数は`REI_ATTENTION_DELIVERY_ENABLED`、`AUTOMATIC`、`ENDPOINT`、`BEARER_TOKEN`、`PROJECTS`（各々同じprefix）。`automatic`も既定false。

配送は`deliverAttention`のPolicyがAUTO_APPROVEのときのみ実行する。既定分類はNETWORK_WRITEとEXTERNAL_SIDE_EFFECT。通常の確認待ちPolicyを背景処理で飛び越えず、BLOCKEDとして保存する。管理者のcapability overrideとPolicy無効時の既存規則は維持する。

## 配送と復旧

自動設定は新規の、保存InboxとProject/Session/Run/ID/種別が一致するATTENTION_REQUIREDだけをキューへ保存する。過去Inboxの一括backfillはしない。手動要求は現在ProjectのOPEN itemを明示する。既存requestはidempotentで、PENDING/SENDING最大256件。1worker・1in-flight・5秒tickに最大1件。HTTP前のclaimとattemptをSQLite保存する。

JSONはschemaVersion、id、projectId、kind、createdAtだけ。message/reference/Session/Run/Tool引数/結果は送信しない。Content-Type application/json、Idempotency-KeyはInbox ID。1 application send attemptに2秒のtimeout、response bodyはdiscard、redirectを追わない。2xxはSENT、非2xxはFAILED、timeout/取消/通信不明はUNKNOWN。受信者がIdempotency-Keyを扱わない場合もあり、network exactly-onceを保証しない。

再起動時はPENDINGを保持し、SENDINGをUNKNOWNへ移し自動再送しない。FAILED/UNKNOWN/BLOCKEDの再送は手動のみで、すでにattemptがある場合は重複リスクを明示確認する。最大3 attempts。SENTは再送不可。保存先hashが設定と異なる要求はBLOCKED。事前ackはSUPPRESSEDだが、送信開始後のackで既送信を取り消せない。Inbox ackも配送もTool承認・Run再開・Goal完了を行わない。

同一SQLiteへ同時に複数のReiアプリを起動する運用は対象外。停止・DB更新失敗で結果不明になる可能性を保持し、外部サービス固有のemail/Slack adapter・providerによる配送確認はこのsliceに含まない。

## 明示操作

- `/attention delivery <id>`: 状態の読取り。
- `/attention deliver <id>`: 手動キュー投入。HTTP送信はworkerのPolicy判定後。
- `/attention retry-delivery <id> --acknowledge-duplicate-risk`: 不確定な前回配送も含む明示再送。
- 認証済み`GET /api/v1/projects/{projectId}/attention/{id}/delivery`。
- 認証済み同URLの`POST`へ`{"retry":false,"acknowledgeDuplicateRisk":false}`。再送なら両方true（未attemptのBLOCKEDはriskfalse可）。Project越境は拒否する。

Nativeの既存Inbox表示・ackはそのまま使える。専用配送操作画面はこのsliceで変更しない。
