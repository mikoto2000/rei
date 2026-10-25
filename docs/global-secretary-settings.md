# YAMLによる全体の生活設定・朝夕ブリーフィング

勤務時間は利用者全体の `rei.life`、朝夕ブリーフィングは `rei.secretary.briefings` で設定できます。既定では登録済みの全Projectを、朝夕それぞれ一つのレポートにまとめます。`independent-projects` に指定したProjectだけは全体から除き、各Projectに独立したレポート・通知を作ります。タスク・Session・自律実行の権限と予算は従来のProject単位のままです。

## 設定ファイルと例

Reiの入力欄で `/config path` と `/config init` を実行し、表示された外部 `application.yaml` を編集します。既存の `rei:` に統合し、既存のモデル接続・権限設定を残してください。変更はReiの再起動後に反映します。ファイルの自動監視・ホットリロードは行いません。

```yaml
rei:
  life:
    zone: Asia/Tokyo
    work-days: [MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY]
    work-start: '09:00'
    work-end: '18:00'
    # 以下の時間帯は例です。生活リズムを推測して自動設定するものではありません。
    notifications: [] # 空でも勤務・休息・既知予定による通知制約は適用
    rest: []          # 未設定。必要なら days/start/end を明示
    personal:
      - days: [MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY]
        start: '19:00'
        end: '21:00'
    background:
      - project: PROJECT_ID_A
        task: TASK_ID
    emergency-notice-ids: []

  secretary:
    enabled: true
    followups-enabled: true
    briefings:
      enabled: true
      morning-time: '08:30' # 勤務開始の前
      evening-time: '18:30' # 勤務終了の後
      # zone: Asia/Tokyo # 省略/空なら、その時点の全体生活設定のzoneを使用
      independent-projects: [] # 全Projectをひとまとめにする
```

`PROJECT_ID_A` と `TASK_ID` は実際のIDに置き換えます。`/session show` のprojectIdを確認できます。自動通知を有効にするには少なくとも一方の時刻が必要です。初期状態では無効・時刻未設定です。`rei.agent-scheduler.enabled` の追加有効化は不要で、既存followupsのtickを使います。朝夕の時刻は毎日に適用し、勤務曜日は通知を抑止する勤務時間の判定に使います。

たとえばProject Bだけを独立させる場合は、次のように指定します。

```yaml
      independent-projects:
        - PROJECT_ID_B
```

この場合、各時刻に「Bを除く全Projectの全体ブリーフィング1件」と「Bの独立ブリーフィング1件」を生成します。独立分にも共通の朝夕時刻・zone・生活制約を適用します。残りが空なら空の全体通知を送りません。登録済みProjectがない場合も自動通知しません。新たに登録したProjectは、次の朝夕レポート生成時に自動で対象になります。作業ディレクトリ全体を探索してProjectを作ることはありません。

独立指定は最大16件で、空・重複したIDや内部の予約scopeは指定できません。登録されていない独立IDは通知を作らず、`briefing-settings` の `projects`（解決済み対象）と `independentProjects`（独立指定）で照合できます。全体にまとめる登録済みProject数には従来の16件制限を適用しません。

以前の `briefings.projects` は互換用の対象限定リストとして残しています。**全Projectを自動でまとめるには、この項目を削除するか `projects: []` にしてください。** 空・省略が既定の全Projectモードで、`briefing-settings` の `allProjects` は `true` になります。既存の明示リストを残した場合は、そのリストの範囲内で全体・独立を分けます。

`life` の通知・休息・個人時間帯は `days` / `start` / `end` の配列です。日付をまたぐ時間帯にも対応します。`exception` は既存の `start` / `end`（ISO instant）/ `availability` で一時例外を指定できます。`revision` はYAMLに書きません。

## YAMLと保存済み設定の優先順位

生活設定の優先順位は **YAMLの明示項目 → DBの保存値 → 既定値** です。未指定項目は保持し、`[]` はその配列を明示的に空にします。配列内の一部追記ではなく、配列全体を置き換えます。起動するだけではDBへ設定を書き戻しません。

CLI/APIで生活設定を変更する場合も、YAMLで管理している項目は変更できません。その項目はファイルを編集して再起動します。YAMLで未指定の一時例外やbackgroundなどは、従来のrevision付きCLI/APIで変更できます。CLI更新は有効な設定全体を保存するため、後でYAMLを外した際のフォールバックはその最後のDB保存値です。古いDB状態へ自動復元する意味ではありません。

不正な時刻・ゾーン・曜日、未知/誤記のYAML項目は起動エラーにし、設定を黙って無視しません。勤務中の観測禁止、未知の空き時間をAVAILABLEとしない扱い、既存の承認条件は維持します。backgroundに登録するだけで自律実行や外部送信を許可するものではありません。[既存の準備設定](life-preparation.md)を併用してください。

## Project指定なしのCLI

通常Shellと軽量CLIで共通です。軽量CLIでも選択中Projectを暗黙に使わず、Project指定がなければ全体レポートを表示します。

```text
/secretary life-settings
/secretary life-status
/secretary briefing-settings
/secretary briefing
/secretary review
/secretary briefing-generate --key global-morning-001
/secretary review-generate --key global-evening-001
/secretary briefing-history --limit 20
/secretary briefing-show GENERATION_ID
/secretary briefing-notifications --limit 20
/secretary briefing-check
```

`briefing` / `review` は現在の読み取り、`*-generate` は手動保存です。手動保存は通知しません。同じキーは日付・種別・対象Project・zoneを照合し、保存済み内容を返します。更新内容を保存する再生成には新しいキーを指定します。`briefing-check` は現在時刻で当日分の設定済み自動通知を一回確認し、時刻前のレポートを前倒ししません。

従来のProject別表示・保存履歴は、明示指定で利用できます。

```text
/secretary briefing --project PROJECT_ID_A
/secretary review --project PROJECT_ID_A
/secretary briefing-history --project PROJECT_ID_A
```

## 自動生成・通知・障害

全体ブリーフィング有効中は、Project別に保存されている朝夕の自動生成時刻を使いません。未配信の個別BRIEFING/REVIEWは抑止し、朝夕の重複を避けます。期限通知等の別の秘書通知設定は維持します。全体機能を無効にすると、従来のProject別設定が再び適用されます。切替前にすでに届いた通知は取り消しません。

集約には既存の台帳・Planning・保存済み記憶・接続キャッシュ・準備結果を使います。新しい観測・外部通信・LLM処理は追加しません。Projectごとの取得失敗は `unavailable` と `partial` で示し、他Projectの情報を残します。今すぐの作業候補には、全体で一つの利用可能時間予算（半分・最大60分・最大3件）を適用します。

Project指定のない通知イベントとして通常Shellへ表示し、既存の認証付き全体イベント経路を使います。通知はRei内のINBOXだけです。全体レポートを既存のProject向けSlack/Webhook等へ自動配送しません。

既存のsnapshotテーブルと通知outboxで再起動・同日再実行・夏時間の重複を扱い、全体グループ・各独立グループで当日の朝夕各1件にします。停止中に時刻を過ぎた場合は当日分だけ追いつきます。勤務・休息・既知予定中はHELDで保持し、解除後に同じ通知IDを使います。保持を配送失敗回数に数えません。全体・独立ともに全体INBOXへ届け、別のProject向けDIGESTには混ぜません。自動生成の作業候補予算は独立分も含めて共有し、分割だけで増やしません。

再起動後に対象Project/zoneが変わった未配信通知や、日付が古くなった朝夕通知は抑止します。すでに保存された同日の自動レポートを設定変更だけで再通知しません。必要なら新しい手動キーで再生成し、履歴を確認してください。

## API・保存・削除

既存Bearer認証下で、`GET /api/v1/secretary/briefing` / `review` と `briefings` / `briefings/ID` のprojectId省略は全体を表します。明示projectIdは従来のProject別操作です。

- `GET /api/v1/secretary/briefings/settings`: 有効な全体設定
- `POST /api/v1/secretary/briefings/generate`: `{"key":"manual-one","review":false}`、任意 `date`。projectIdなしで全体生成
- `GET /api/v1/secretary/briefings/notifications`: 全体通知履歴
- `POST /api/v1/secretary/briefings/check`: body `{}`、当日分の時刻確認
- `POST /api/v1/secretary/briefings/delete`: body `{}`、全体の保存レポート削除

全体・独立の自動生成履歴は `/secretary briefing-history` と `briefing-show` で確認できます。独立分は `independentProject` に対象IDが入り、通知文にも独立Projectを表示します。`briefing` / `review` のProject省略は独立分を除く全体グループの読み取りです。明示 `--project` の手動表示・保存は従来どおりProject単位で利用できます。

全体・独立の自動生成履歴は既存storage.db内で従来のProject別履歴と分離し、合計で最新100件に制限します。元タスクや実行状態を複製した別台帳は作りません。

```text
/secretary briefing-clear
```

削除対象は保存レポートだけで、台帳・通知受付/監査記録・バックアップは削除しません。既存の保存・バックアップ管理方針を併用してください。実外部サービス・実画面/マイクの到達性は、この設定変更のローカル検証の対象外です。
