# Activity 保存観測時 Work Context HTTP

認証済み `GET /api/v1/projects/{projectId}/activity/observation-context?date=today`。dateはtoday/yesterday/YYYY-MM-DD、Activity journal timezoneにおける当日または過去の1日。Project IDは登録済みIDだけで、入力をpathとして扱わない。

schemaVersion=1、scope=PROJECT_OBSERVATION_CONTEXT、projectId、実date、zone、partial、missingContextRecords、linkedObservations、reportを返す。保存Activityの選択ProjectとWorkReference Project/観測時刻が一致する観測時metadataだけを使用する。現在のWork Context、Git、履歴からの補完・再推定は行わない。以前のEVENT_REFERENCE照合は既存Shell経路で継続し、このHTTPは保存OBSERVATION_CONTEXTだけを返す。

前景application、保存Git/取得時刻、Work Context revision/更新時刻、itemの申告状態/確度、TOOL由来のevent/session/run/turn/command参照・相対file・出典時刻を表示する。コマンド本文・結果・task本文・画像・window titleを返さない。現在のtask従事・成果・集中の証明ではない。

旧記録・所有者/時刻不一致・未来の文脈やTOOL出典はmissingとして区別する。Projectを特定できない旧標本や別Project標本からは推測しない。partialには保存snapshotの不完全性、128 link上限、表示文字列の切詰めを含む。partial時のmissing件数は確認できた走査prefixの件数で、1日全体の完全件数ではない。

1日最大5000 Activity records、既存SQLite per-record128KiB/合計64MiB読取り、saved snapshot各20 items/各8 sources、処理2秒、report32768charsを上限とする。超過時は422としてreportを返さず、部分結果を完全結果として公開しない。linkの表示上限128はpartialを付けて表示する。SQL内部IO量の保証ではない。旧ActivityStoreの非bounded読取りへfallbackしない。機能/port未提供は503、未登録Projectは404、不正/未来日は400。

追加観測・Git取得・モデル・Run・Work Context更新は行わない。Source metadataは標本に保存された申告/参照であり、別システムの現在の事実を再検証した証明ではない。Native専用読取りUIは後続。
