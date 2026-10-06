# Activity 期間Coaching

週月分析の保存済み標本を使う補助機能。手動評価は追加撮影、LLM、通知、音声、Tool実行、タスク変更を行わない。後続で [opt-inの自動週月通知](activity-automatic-period-coaching.md) を追加した。既定では手動のみ。既存Behaviorの現在娯楽判定・基準・cooldown設定は変更しない。

```text
/activity coaching status
/activity coaching configure --categories development,research,documentation --target-share 0.6 --min-observed-minutes 120 --min-coverage 0.1 --max-unknown-share 0.25 --cooldown-days 7
/activity coaching on
/activity coaching weekly
/activity coaching monthly 2026-09-15
/activity coaching off
```

既定は無効。基準と有効状態はActivity journalと同じSQLiteへ保存し、再起動後も保持する。Project別設定ではない。分類カテゴリはユーザーが選び、成果や娯楽の意味をカテゴリだけから決めない。`configure`は全基準を指定値または表示した既定値で置き換え、適用前に無効にする。明示的な`on`で適用する。過去の重複抑制・cooldown記録は変更でリセットしない。

日付省略時は最後に完了した暦週（月曜始まり）/暦月と、その直前期間を比較する。日付指定時はその日を含む暦期間。ActivityのzoneとProject aliasを利用し、未来日付を拒否する。現在の途中期間では助言しない。

両期間が最小観測推定分・最小観測率・最大判定不能率を満たす場合のみ、指定分類の推定時間比率を比較する。観測率の分母は暦期間全体。分類比率の分母は分類可能な観測推定時間（unknown/other除外、idle含む）で、未観測時間を補完しない。

指定比率以上なら助言なし。指定比率未満なら「分類の妥当性と今の優先事項を確認し、必要なら短い作業枠を試す」という提案を示す。前期間から10 percentage points以上減った場合は別の理由コードを記録するが、成果・生産性の低下や集中・中断を断定しない。比率・前期間値・基準・観測推定分と、推定である旨を表示する。

助言の直前に、設定revision、有効状態、同一期間キー、週/月共通cooldownをSQLiteの単一書込transactionで再確認する。同じzone/暦期間は理由や基準が変わっても一度限り。別期間でも最後の表示予約から設定日数が経過するまで抑制する。off/onや再起動も履歴を消さない。予約後にShell表示が失敗した場合も再表示しない、保守的なat-most-once方式。DB操作失敗時には助言を出さない。

抑制コード: `DISABLED`, `PARTIAL_PERIOD`, `INSUFFICIENT_OBSERVATION`, `UNCERTAIN_CLASSIFICATION`, `WITHIN_USER_CRITERIA`, `ALREADY_SHOWN`, `COOLDOWN`, `SETTINGS_CHANGED`。

保存する表示予約は期間キー・理由コード・予約時刻・設定revisionだけ。画面内容、自由記述、Project名、モデル回答は保存しない。記録は重複抑制のため保持する。

バックグラウンド週月通知は共通品質gate・予約/cooldownを再利用して対応済み。専用分析Web/Native UI、意味的な助言の個人化、成果・生産性の実測は追加候補。短時間の既存Behavior通知には従来の設定・抑制が適用される。
