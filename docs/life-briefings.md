# 朝夕ブリーフィング

勤務・休息の設定は [生活コンテキスト](life-context.md)、既存の台帳・実行権限は [秘書機能](secretary.md) を参照してください。

## 操作

`rei.secretary.enabled=true` で明示操作を利用できます。自動生成には既存Schedulerの `rei.secretary.followups-enabled=true` とProjectの監視・通知の有効化が必要です。初期状態は無効、朝夕時刻は空です。次は設定例であり、推奨する生活リズムではありません。

```text
/secretary configure --project PROJECT --watch-enabled --notifications --briefing-time 08:00 --review-time 19:00 --zone Asia/Tokyo --destination INBOX
/secretary briefing --project PROJECT
/secretary review --project PROJECT --date 2026-10-26
/secretary briefing-generate --project PROJECT --key morning-20261026
/secretary review-generate --project PROJECT --key evening-20261026-v1
/secretary briefing-history --project PROJECT --limit 20
/secretary briefing-show ID --project PROJECT
/secretary show TASK_ID --project PROJECT
/secretary sources TASK_ID --project PROJECT
```

`briefing` / `review` は現在の読み取り表示、`*-generate` は取得時点の保存です。同じProject・キー・日付・朝夕種別の再要求は保存済み内容を返します。手動再生成には新しいキーを指定します。同じキーで日付・種別を変えると競合です。手動生成は通知しません。通常CLIと軽量terminalの操作は同じです。

既存Bearer認証のAPI:

| 操作 | API |
| --- | --- |
| 朝/夕の現在表示 | `GET /api/v1/secretary/briefing?projectId=PROJECT` / `review`（任意 `date`） |
| 手動生成 | `POST /api/v1/secretary/briefings/generate` |
| 履歴 | `GET /api/v1/secretary/briefings?projectId=PROJECT&limit=20` |
| 保存済み内容 | `GET /api/v1/secretary/briefings/ID?projectId=PROJECT` |
| 明示削除 | `POST /api/v1/secretary/briefings/delete`、body `{"projectId":"PROJECT"}` |

生成body例: `{"projectId":"PROJECT","date":"2026-10-26","review":true,"key":"evening-20261026-v1"}`。Projectをまたぐ参照はできません。候補の実行には既存の `work-start` / `feedback --reaction EXECUTE` と従来の承認・予算・LifePolicyを使います。履歴閲覧や候補の掲載は実行承認になりません。

## 内容・信頼性

朝は今日・近い期限・重要未完了・待機・準備候補・前日以降の更新、夕は完了・未完了・背景処理結果・保留通知・明日以降の期限・継続案件を整理します。重要度、期限、安定したID順で表示します。既存のPlanning / Scheduler / Waiting / WorkContext（保存済み記憶）/ 接続キャッシュ / 実行結果 / Attention受信箱を参照し、新たな観測・Web取得・LLM呼出は行いません。

`evidence` は情報源、ID、参照先と次の分類を持ちます。

- `FACT`: 保存された実行状態やツール証拠。外部の主張まで事実と認定しません。
- `USER_REGISTERED`: 利用者が登録・確認した情報。完了や実行許可の保証ではありません。
- `INFERENCE`: 未確認の抽出候補、記憶からの推測。
- `PROPOSAL`: アシスタントの提案・生成下書き。内容を独立に検証した意味ではありません。
- `UNKNOWN`: 不明、由来が未分類のprovider、未検証の外部情報。

外部情報は引き続きUNTRUSTEDです。詳細は明示的な接続readで確認します。業務予定は常に不明と表示し、予定や期限を作りません。カレンダーの空白を空き時間に変えません。現在の生活制約が明示的なAVAILABLEの場合だけ、任意の作業候補を最大3件示します。UNKNOWN・勤務・休息中や過去未来の日付では割り当てません。

## 障害・重複・保存

自動生成は既存Schedulerのpollに統合し、当日の設定時刻を過ぎたら当日分だけ追いつきます。過去日を大量生成しません。Project・朝夕・現地日付の既存outboxキーで二重通知を防ぎ、保存後・enqueue前の停止は保存済み内容から再開します。再起動・夏時間の重複時刻・同日のゾーン変更でも日付キーを再利用します。ゾーン変更で別日になれば別日として扱います。

未設定Projectのゾーンは生活設定のゾーンを使います。明示設定済みProjectのゾーンは維持し、生活設定と違えば注意表示します。通知直前の勤務・休息判定は生活設定を優先し、既存HELD/DIGEST・outbox再試行で配送します。生成と配送は別の状態です。

providerの部分障害は `partial=true` と注意表示にし、取得済み情報を残します。モデルは使わないためLLM障害の影響はありません。台帳・保存DB自体を読めない場合はエラーです。保存失敗時は通知せず次の既存tickで再試行します。保存後の更新は現在表示または新キーで確認してください。

`storage.db` のschema17に生成結果だけを保存し、原本の所有者は変更しません。Projectごとに最新100件を保持し、古い生成キーの再利用保証は保持中に限ります。情報源は最大16 provider×256件、タスク1,000件、秘書保留通知は重要度順20件です。上限到達は部分情報として扱い、原本は既存詳細APIで確認できます。

```text
/secretary briefing-clear --project PROJECT
```

履歴削除は台帳・通知outboxを削除せず、配信済み通知も再配信しません。バックアップには削除前のデータが残る場合があります。既存の停止中バックアップ管理手順で管理してください。

## 手動確認

1. 初期状態が無効・時刻未設定、手動生成が通知しないことを確認します。
2. 同キー・再起動後も同内容、新キーで別履歴になることを確認します。
3. 設定時刻後に起動し、当日各1件だけ生成することを確認します。
4. 勤務中は保留、勤務後は既存集約、休息中は再保留になることを確認します。
5. 不明業務予定・部分失敗・未検証情報が明示され、UNKNOWNでは `next` が空になることを確認します。
6. `briefing-show`、`show`、`sources`、既存実行詳細を辿ります。

実外部サービス・実画面・マイクへの到達性はローカル検証の成功に含みません。
