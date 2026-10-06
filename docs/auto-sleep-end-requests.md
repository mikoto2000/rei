# Session／アプリ終了時のAuto Sleep要求

`rei.memory.auto-sleep.enabled=true` と memory有効を前提に、`on-session-end`／`on-shutdown` を個別に明示有効化する。環境変数は `REI_MEMORY_AUTO_SLEEP_ON_SESSION_END`／`REI_MEMORY_AUTO_SLEEP_ON_SHUTDOWN`。両方の既定はfalseで、旧4／6引数constructorもfalseを維持する。通常のidle／cron動作は従来通り。

`/session end` は現在のShell clientの選択を解除し、Session metadata／会話履歴を残す。再開可能で、Runを起動・取消・承認しない。認証済みHTTPは `POST /api/v1/sessions/{sessionId}/end` と `{ "projectId":"保存SessionのProject ID" }` で同じ終了境界を使う。API client自身の選択はclient側で管理する。他Project・未存在Sessionを拒否し、保存失敗時はShell選択を解除しない。単なるSession切替・Native画面を閉じる操作を自動endと解釈しない。

終了はモデルを呼ばず、既存Memory SQLiteへ要求を保存する。Sessionごとにcoalesceし、最大256件・正確なProject所有者・増加revisionを保持する。古いrevisionによる完了／取消で新しい要求を消さない。既存metadataから発見可能な最大256候補はgracefulアプリ終了時も保存し、次回起動後へ引き継ぐ。shutdown保存障害はclassだけを記録し、他候補と既存worker終了処理を継続する。hard kill／OS crash時のhook実行は保証しない。

次のidle tickは保存要求を優先し、対象causeのflagが現在有効・Projectが登録済みの場合に処理する。busy／minimum-idle／activity version取消・worker1・retry cooldown・既存Sleepモデル予算とcheckpointは維持する。要求対象は1未処理turnから整理でき、cronの未来時刻と通常minimum-turnsを待たない。cronの予定時刻は前倒し要求で消費しない。送信済み費用・失敗・取消は既存予算の規則を使い、要求を保持する。未処理turnがなくなったときだけ、読んだrevisionを削除する。未知Project・無効causeの要求は保存したまま自動実行しない。

`/sleep requests` で選択ProjectのSession／revision／causeを確認し、`/sleep cancel-request <sessionId> --revision <確認値>` で未処理要求を取り消す。既に開始したSleepを停止する操作ではなく、古い確認値・他Project・未存在要求を拒否する。要求確認／取消はモデルを呼ばず、memoryを無効にした後も利用できる。再登録・再終了はSleepの累積予算やcooldownを補充しない。
