# Native Activity Coaching 設定 実装記録

B9の専用設定UIをActivity週月分析画面へ追加した。明示GETで端末全体設定を読み、criteria変更の前後確認→無効状態で保存、別確認→保存済み基準の有効/無効化へ接続する。編集は確認を取り消し、draftを保存済みと混同しない。

Native/React未実装APIのRed→最小Green→Native5新規・React7新規/関連PASS。固定認証endpoint・必須flag・schema/scope/criteria/安全整数revision・保存echoを検証し、確認前POST、古い所有者応答、lock、変更後確認再利用、未知scope、入力不正、結果不明時の自動再送を防いだ。

全体回帰Native101 tests、React79 tests/22 files、typecheck PASS。Java変更なし、同じserverコードは直前3290 tests/611 suitesの全回帰とMerge後Coaching関連PASS済み。feature 28707502をCommit/Pushしmain 53d18facへMerge済み。Merge後Native8・React17関連テストPASS、main Push済み。

[操作・所有者境界・不明結果・設定API連携](native-activity-coaching-settings.md)。追加LLM・Run・観測・助言評価/予約なし。意味的個人化・Activity/Work Context専用詳細UIは本設定UIで完了扱いにしない。
