# Activity Coaching 設定 HTTP API

既存Activityが有効な接続先の端末全体に対するCoaching設定。既存API認証が必要。Project候補から所有者を推定しない。追加モデル、観測取得、助言評価・配信・cooldown予約をHTTPの閲覧/設定変更で行わない。

- `GET /api/v1/activity/coaching`: schemaVersion=1、scope=LOCAL_DEVICE_COACHING_SETTINGS、revision、settingsを読む。
- `POST /api/v1/activity/coaching/settings`: `{expectedRevision,settings}`。settingsは既存7フィールド（enabled/categories/targetShare/minimumObservedMinutes/minimumCoverage/maximumUnknownShare/cooldownDays）。enabled=falseの設定を明示保存し、保存後は無効状態にする。
- `POST /api/v1/activity/coaching/enabled`: `{expectedRevision,enabled}`。enabledを必ず指定し、確認した設定を有効/無効へ変更する。

両POSTは必須expectedRevisionをSQLite BEGIN IMMEDIATE transactionで照合する。一度成功するとrevisionが増え、古い確認・同じPOSTの再送は409となる。409や通信結果不明時はGETで状態を再確認し、新しく明示操作する。revision/必須flag/分類や比率基準が不正なら400、Activityまたはrevision付き更新portが未提供なら503。旧portへの無条件更新fallbackはしない。

既存Shell設定は従来どおり使用できる。HTTPとShellの変更は同じrevisionを更新し、自動Coachingの既存settings変更gateへ反映される。設定変更で期間receiptやcooldown履歴を消さない。revisionのlong上限では増分を拒否し、overflowやversion復元を行わない。

有効化は既存のmanual/automatic評価経路で助言を許可する設定であり、HTTP操作自体は助言を出さない。自動評価には別途既存automatic opt-in設定・観測品質・idle/context gate・重複抑制が必要。専用Native設定画面は後続。
