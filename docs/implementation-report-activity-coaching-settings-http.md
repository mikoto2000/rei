# Activity Coaching 設定 HTTP 実装記録

B9の専用UIに必要な認証済み設定GET・明示criteria保存・enabled変更を既存Coachingへ接続した。端末全体scopeを明示し、Project候補から所有を推定しない。設定保存は無効状態、別POSTで確認した保存設定を有効化/無効化する。両POSTは必須revisionをSQLite BEGIN IMMEDIATE内で照合し、GETや変更で評価・観測・助言予約を行わない。

未実装controller/CAS portのcompile Red→2テストGreen→7新規テスト・既存manual/automatic/期間HTTP関連PASS。同時2 instanceの片方のみ成功、古い確認・再送・省略flag・不正criteria・未認証拒否、再起動・receipt保持、旧port非fallback、revision overflow拒否を検証した。

全体回帰3290 tests / 611 suites、failure/error/skip各0。feature f6226a77をCommit/Pushしmain 8ddb86ffへMerge済み。Merge後関連テストPASS、main Push済み。Native/React変更は後続。

[HTTP形・version競合・設定/有効化・既存gate](activity-coaching-settings-http.md)。専用Native UI・意味的個人化は本APIでは完了扱いにしない。
