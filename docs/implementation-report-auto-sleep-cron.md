# Auto Sleep cron / 日次設定

任意の6フィールドcron式とタイムゾーンを追加。未指定時は従来idle監視を維持する。日次は同じcron設定で扱う。既存enabled / memory.enabled / idle / busy / minimumTurns / retryInterval / cancellation / checkpoint境界を維持する。

稼働中に延期した予定は1回へ集約し、1機会あたり最大1バッチ・256候補走査。候補がなくてもその機会を消費する。停止中のcron発火は再生せず、再起動後の次回時刻から開始する。永続Sleep cursorは保持する。

TDD: 未実装recordコンストラクタでRedを確認。追加5テストは予定前の無実行、予定時刻、タイムゾーン、活動による延期/集約、不正設定、Spring設定bindingを確認。全体 -Pfull test は2,850 tests / 546 suites、failure 0 / error 0。git diff --check成功。

Session終了triggerと外部metadata監視はDeferred。停止中の発火を永続管理する汎用Scheduler cronとは別のAuto Sleep設定である。
