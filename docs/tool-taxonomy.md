# Tool taxonomy

LLM に公開する Tool は、ユーザーの目的に近い workflow Tool を通常入口とし、細粒度制御用の primitive Tool を escape hatch として残す。category metadata は導入せず、各 Tool の name と description で routing する。

## Default Tool inventory

| Role | Tools |
|---|---|
| Workflow / preferred | `runCommand`, `searchAndRead`, `readMultiFile`, `writeMultiFile`, `webSearchAndRead`, `searchKnowledge`, `feedSummarizeItem`, `feedSummarizeBriefing`, `dailyBriefing` |
| Shell / process control | `executeExternalProgram`, `getShellProcessStatus`, `killShellProcess` |
| Code and file primitive | `findFile`, `listFile`, `grepMultiQuery`, `applyTextDiff`, `readPdfFile`, `readBinaryFile`, `writeBinaryFile`, `createDirectories`, `copyFile`, `moveFile`, `deleteFile` |
| Web primitive | `webSearch`, `fetchUrlContent` |
| Feed management | `feedList`, `feedAdd`, `feedDelete`, `feedUpdate` |
| Task and reminder | `taskList`, `taskCreate`, `taskUpdate`, `taskComplete`, `taskUpdateDeadline`, `taskDelete`, `reminderCreate`, `reminderList`, `updateTaskState` |
| Calendar and scheduling | `googleCalendarListEvents`, `googleCalendarListEventsForDate`, `googleCalendarCreateEvent`, `scheduleAt`, `scheduleAfter`, `listScheduledActions` |
| Utility / integration | `today`, `now`, `getCurrentTime`, `soundNotify`, `blueskyPost` |

`rollDice` は利用箇所のない demo utility だったためメソッドを残したまま `@Tool` を外し、default Tool set から除外した。

## Annotated but not in the default registration

次の Tool class はコード上に存在するが、`AiConfiguration` / `LlmChatClientProvider` の default Tool object 一覧には含まれない。

- Conversation history: `getConversationHistory`, `searchConversationHistory`
- Vector document: `vectorDocumentAdd`, `vectorDocumentList`, `vectorDocumentSearch`, `vectorDocumentDeleteByDocId`, `vectorDocumentDeleteBySource`

これらは用途と公開方針が明確になるまで今回変更しない。

## Routing

- 公開 Web の通常調査: `webSearchAndRead`
- 検索結果 metadata / URL 候補だけ: `webSearch`
- 既知 URL の取得: `fetchUrlContent`
- 登録済み Knowledge Base と Web の統合調査: `searchKnowledge`
- 通常の Shell 実行: `runCommand(auto)`
- 明示的 foreground/background 制御: `runCommand` の `foreground` / `background` mode
- 場所が不明なコードやテキストの検索と読取: `searchAndRead`
- 一致位置だけ: `grepMultiQuery`
- 既知ファイルの読取: `readMultiFile`
- 複数ファイルの全内容書込: `writeMultiFile`
- 既存ファイルの局所編集: `applyTextDiff`

旧 Shell メソッドは内部互換コードとして残し、新規 LLM Tool definition からだけ除外する。保存済み Tool event や会話履歴の表示データは書き換えない。Tool object 内の annotation method 順序を framework が公開順として保証していないため、表示順制御にも依存しない。

## 整理候補

- `today` / `now` と `getCurrentTime` の重複
- `executeExternalProgram` と Shell workflow の境界
- default registration 外の conversation-history / vector-document Tool の公開方針
- domain CRUD Tool が増えた場合の feature 単位 Tool set 分割
