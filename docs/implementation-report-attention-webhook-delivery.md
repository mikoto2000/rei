# Attention外部webhook配送 実装記録

A6の外部配送へ、既定無効・明示Project・管理者の単一送信先・Policy許可に限定する永続outboxを追加した。保存事実だけをeventと照合し、本文やTool引数を除くmetadataだけを送信する。HTTP前claim、最大256 pending/1worker/2秒/redirect禁止/最大3 attempts、restartで不確定配送をUNKNOWNとして保持し、手動risk acknowledgementなしには再送しない。

未実装APIのcompile Red→実SQLiteとローカルHTTPの6テストGreen→境界検証へ進めた。HTTP明示request fixtureの不足したboolean flagsを修正した後、関連テストPASS。新規13テストで、実AttentionService経由の新規通知、既定・自動・Project・Policy gate、metadataとcredential/idempotency headers、失敗・手動再送・restart・ack・送信先変更、偽造event、並行容量・claim、取消、実Shell/認証HTTP、config binding、redirect非追跡・実timeoutを検証した。検証はローカルfixtureだけで、実外部サービスへ通知していない。

全体回帰は3243 tests / 605 suites、failure/error/skip各0。feature ae83747bをCommit/Pushし、main f3618d35へMerge済み。Merge後関連テストPASS、main Push済み。Java/configのみでNative/React変更なし。

[設定・明示操作・結果不明・配送保証の範囲](attention-webhook-delivery.md)。残件は監査表で管理する。
