# GitHub Event Trigger

GitHub adapterは既存のScheduler event triggerを使う。新しい実行queueは作らない。
既定OFF。Web API、明示的なrepository→登録済みProject/Session mapping、32文字以上の
Webhook secretが必要。Secretは環境変数 `REI_GITHUB_WEBHOOK_SECRET` で渡す。
GitHub側のWebhook URLは `POST /api/v1/github/events`、Content typeはapplication/json。
受信側のTLS公開方法は運用環境で設定する。

```yaml
rei:
  web:
    enabled: true
  github:
    webhook:
      enabled: true
      secret: ${REI_GITHUB_WEBHOOK_SECRET}
      events: [pull_request, pull_request_review, workflow_run]
      mappings:
        - repository: owner/repository
          project-id: REGISTERED_PROJECT_ID
          session-id: EXISTING_SESSION_ID
          branch: main
          pull-request: 17
          notify-review: true
```

branchはPRのbase branchまたはworkflowのhead branch。branch/PRを省略するとそのrepository内の
全対象事実に一致する。Mappingは最大64、未登録Project/Sessionは起動時に拒否する。
Project rootの変更後は旧mappingの処理・表示を拒否し、設定を見直して再起動する。

## 受信と保存

raw bodyのHMAC-SHA256を `X-Hub-Signature-256` と定時間比較し、検証後にJSONを読む。
必須headerはdelivery UUID、event名、署名。重複header、Content-Encoding、未知event、
重複JSON key、末尾JSON、深さ32超、無効な番号/日時を拒否する。既定payload上限256KiB、
設定可能な上限1MiB。秘密・raw payload・PR本文・コメント・タイトル・URLは保存しない。
固定のrepository、PR、branch、SHA、状態、発生時刻だけを事実として保存する。

GitHubの署名はbodyを対象とし、delivery/event header自体の署名ではない。
payload shapeとeventの一致を検証し、delivery IDとbody SHA-256の両方をSQLiteで永続重複排除する。
同じdeliveryで異なるbodyは409。異なるdeliveryで同じbodyも既処理として返す。
payloadの日時は既定7日以内、未来のずれは最大5分。許容した未来時刻のtrigger比較には受信時刻を使う。
署名だけでは初回受信した古いpayloadの真偽を確認できないため、日時制限と任意の現状態再確認を組み合わせる。
receipt既定10000/最大100000、fact既定50000/最大100000。上限は507で拒否し、自動削除しない。

receipt、fact、既存Schedulerの遷移、Inbox、通知発行待ちを同じSQLite transactionに保存する。
途中失敗時は全体rollbackする。通知は5秒間隔、16件ずつ、固定event IDで発行し、発行失敗は再起動後も再送する。
通知の外部配送は既存のProject allowlist、明示設定、Tool permission、配送outboxに従う。
Projectを削除・移動した場合の旧root通知はSUPPRESSED相当の保存状態にして発行待ちから外す。
送信成功として扱わず、他Projectの後続通知を塞がない。
外部送信の成否が不明な場合の再送は既存の明示確認契約を維持する。

## Trigger、Inbox、Dependency、Task

Bearer認証の `GET /api/v1/github/mappings?projectId=...&sessionId=...` でsourceIdを得る。
特定PRまたはbranchを持つmappingは `github:` とSHA-256のsourceIdを返す。
全repository mappingはsourceId=nullなので、特定PR/branchのmappingを追加してtriggerを登録する。

`scheduleOnEvent(sourceId, eventType, expiresAfter, reviewedAction, sessionId)` は既存のPENDING登録。
内容を確認し `/timer activate ID` で明示的に有効化する。既存のScheduler実行許可と予算を使う。
対象eventは `GITHUB_PR_UPDATED`、`GITHUB_PR_MERGED`、`GITHUB_REVIEW_SUBMITTED`、
`GITHUB_CI_FAILED`、`GITHUB_WORKFLOW_COMPLETED`。成功CIはCI_FAILEDに一致しない。
Webhook本文からactionを作らず、有効化前の事実で過去のtriggerを起動しない。

Review通知はnotify-review=true、CI失敗とPR mergeは一致mappingのInboxに保存する。
確認済み操作は許可付与・Task再開・Dependencyへの回答を行わない。
Task ManagerのScheduler結果には `GITHUB_EVENT` fact IDを付ける。
Bearer `GET /api/v1/github/facts/{id}?projectId=...&sessionId=...`、
`GET /api/v1/github/deliveries/{deliveryId}/facts?projectId=...&sessionId=...` は所有範囲だけを返す。

Dependencyの `GITHUB_PR_MERGED` はtarget `owner/repository#17`、expected省略。
同じProject/root/Sessionの認証済みmerge事実がある場合だけCOMPLETEDへ進める。
未受信はWAITING、adapter未設定はBLOCKED。HTTPやAgentをDependency probeから実行しない。

## 任意の現状態再確認

`recheck-required=true` は読み取り専用 `GitHubStateVerifier` Beanを要求する。
CONFIRMED以外は409として事実・triggerを保存しない。Bean不在なら起動を拒否する。
再送済みreceiptは再確認を繰り返さない。実GitHub API adapterは運用依存の拡張点であり、
今回のfixture検証はAPI tokenや外部GitHubアクセスを使っていない。

確認した公式仕様:
[Webhook署名検証](https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries)、
[Webhook運用](https://docs.github.com/en/webhooks/using-webhooks/best-practices-for-using-webhooks)、
[eventとpayload](https://docs.github.com/en/webhooks/webhook-events-and-payloads)。
