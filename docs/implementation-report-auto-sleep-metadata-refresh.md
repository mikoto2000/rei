# Auto Sleep metadata 定期再検出 実装記録

A1 の稼働中の保存済み metadata 再検出へ対応した。起動時の既存 snapshot 走査後、retry-interval ごとに SessionRepository.findPage を使い、新規会話なしで候補を発見する。1 tick は最大100件の1ページ、候補256件、project/session境界・idle/busy/cron・単一worker・取消を維持する。読込障害／上限違反は例外クラスのみログし、次の interval まで再試行しない。

初期 Red は、実 FileSessionRepository の起動時空一覧へ外部から metadata を追加しても Sleep が開始されない1 failure / 0 errors。修正後 Green を確認し、100件ページ→次tickの継続、snapshot再取得なし、障害の間隔制限と復旧、disabled/busy/recent activity時の非読込、project/session境界、上限違反の処理を6テストで検証した。外部変更のテストは実ファイルを使用し、Sleepの開始をmockで観測した。live provider品質を検証するものではない。

全体回帰は3165 tests / 599 suites、failure/error/skip各0。feature `944cdb6d` をPush、main `6b5e54a3` へMerge。Merge後の AutoSleepMetadataRefreshTest / AutoSleepServiceTest / SleepServiceTest / SleepModelBudgetTest / SleepPersistentBudgetTest はPASS、main Push済み。Java変更のみでNative/Reactは再実行していない。

[走査方式・ファイル読込の限界](auto-sleep-metadata-refresh.md)。アプリ終了時triggerは残件。
