# Streaming会話メモリの保存修正

## 原因

RunScopedAdvisorがPromptChatMemoryAdvisorをラップするとき、BaseAdvisorの標準stream処理を呼んでいた。PromptChatMemoryAdvisor固有のストリーム集約処理が迂回され、finish reasonの付いた最後のdeltaだけがafterへ渡っていた。

これにより、最後のdeltaが本文なしの場合はSQLiteの `SPRING_AI_CHAT_MEMORY.content` のNOT NULL制約違反、本文がある場合は回答の末尾だけの保存、finish reasonがない場合は回答未保存が起こった。

## 修正

BaseChatMemoryAdvisorをラップするときはChatClientMessageAggregatorを通し、ストリーム完了時に集約された回答を一度だけafterへ渡す。各deltaは従来どおり利用側へ流し、RunContextをbefore / afterで復元する。delegateのscheduler設定も維持する。

DB制約の緩和、nullを含む終了deltaの単純な除外、既存データの書き換えは行わない。

## TDD / 検証

実SQLiteとSpring AIのJdbcChatMemoryRepository / PromptChatMemoryAdvisorを使い、次を先にテストした。

- 本文なしの終了deltaでも回答全文を一度だけ保存。
- 本文ありの終了deltaでも、それ以前の回答を失わない。
- finish reasonが省略されても、正常終了したストリームの全文を保存。
- 既存履歴、別Conversationの履歴、deltaの配送、RunScopeの復元を維持。

修正前は3件とも失敗（2 failures / 1 error、target/null-memory-red.log）。修正後は上記とRunScopedAdvisor / Intervention / Stagnation / Project切替の対象テスト10件が成功（target/null-memory-green.log）。

全体テストとJAR生成:

```powershell
.\mvnw.cmd -o '-Dmaven.repo.local=C:\Users\mikoto\.m2\repository' '-Drei.data-dir=F:\project\rei\target\test-rei-data' package
```

全体テスト **1,402件、Failures 0、Errors 0、Skipped 0**。実行用 `target/rei-0.0.1-SNAPSHOT.jar` の再生成も成功（2026-09-09 13:36 JST）。結果はtarget/null-memory-package.logに記録。実LLMサーバーを使う手動確認は未実施。

ProjectRegistryと移行データのID不一致は別問題であり、今回の修正はデータ統合を行わない。
