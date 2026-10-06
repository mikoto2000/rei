# Automatic weekly/monthly Coaching

Status: Implemented

Branch: `codex/automatic-period-coaching`

Commit / Merge: 独立feature commitとmain merge commitで記録。

Merged into: `main`

Implemented:
- 既存PeriodCoachingの事前評価とSQLite予約を分離して再利用。既存手動表示・基準・期間キー/cooldownを維持。
- 明示設定と保存基準の二重opt-in。Activity無効/pause・Chat実行/文脈・設定変更・品質不足を抑制。
- 間隔60〜86400秒、単一worker/queueなし、最新完了週/月のみ最大2評価/1通知。月次・週次・手動の共通予約。
- 既存Activity通知・Project/Session所有者・会話履歴・Native表示経路を再利用。追加LLMなし。
- 配信前の一回予約は失敗/クラッシュでも解放せず、自動再送しない。設定テンプレートとShell表示/guideを更新。

Tests:
- Red: 未実装の自動通知API/設定を参照するテストが失敗。
- Green: Coaching/Activity/Behavior/既存通知関連がPASS。
- Fullで新設定の一覧検査不足を検出。既存のexact-key検査を維持し、新3項目と間隔の明示期待値を追加。
- Full再実行: 3026 tests / 578 suites、failure/error/skipped各0、exit 0。
- 実SQLiteの再起動・共通cooldown・無効/busy/pause/設定変更・配信失敗後の非再送・既定opt-outを確認。

Result: PASS

Remaining:
- 意味的個人化、専用分析UI、OS/外部への配送は追加候補。
- 通知予約は配信成功の証明ではない。DB/表示の分散transactionや過去期間の一括再送は行わない。
