# 会話履歴Shellコマンド実装報告

## 1. 調査結果

既存のslash commandは `UserInputParser` / `UserInputService` が解析し、picocliのRootCommandへ渡す。`/project` はnested subcommandを登録しており、`/history` の既存登録はなかった。引用符付き引数・空白区切り検索は同じparserを使用する。

永続会話ログは `ConversationLogStore` が `projects/<ProjectId>/conversations/*.jsonl` に保存する。ConversationIdは `project:<UUID>:<logical-id>`。ProjectRegistryがUUIDと表示名・rootを持ち、Shell選択先はProjectServiceのAtomicReferenceで管理する。現在の通常会話は `ConversationIds.chat()` の `chat:main` 固定で、別会話を選択するShell機能はない。

`ConversationHistorySearchService` / `ProjectHistoryRetrieval` が既にProject横断検索を持ち、`HistorySearchScope` も存在する。結果にはProjectId・Project名・ConversationId・時刻・本文・関連度・境界説明がある。Agent内部検索はAgentRunScopeに基づくが、ShellはProjectServiceの選択先を明示して取得する。

永続ログのmessage modelはConversationId・category・speaker・OffsetDateTime・content。readerはspeakerを制限しない。ChatExecutionServiceは通常user/assistantとUserInterventionを同じログへ保存する。tool/systemが永続ログにある場合も表示対象とする。記録されなかった過去のtool/system payloadは復元しない。

既存の秘匿処理はToolEventCallbackDecoratorのquoted secretとAgentEventFactoryのsecret assignment処理に存在した。これらをCredentialRedactorへ抽出・共通化し、historyでも使用する。Authorization/Bearer、refresh token、credential、private keyにも対応した。

Shell出力は既存端末Writerを保持して文字コードを維持する。既存APIはログ全件をListへ読み込んでいたため、今回の一覧・直近表示・検索には逐次読み取りを追加した。AgentRunのmailbox判定へslash commandを渡さず、`history` を即時実行するcontrol commandとして接続する。

## 2. コマンド仕様

```text
/history
/history list [--project PROJECT] [--limit N] [--offset N]
/history show [CONVERSATION] [--project PROJECT] [--last N | --all]
/history search [--current | --all] [--limit N] QUERY...
/history --help
```

`--project` は登録名またはUUIDを受け付ける。UUIDを先に照合し、同名候補が複数ならエラーにする。listはConversationId・最終更新日時・メッセージ数を表示し、空の一覧も正常終了する。showは各messageの時刻・speaker・本文を表示する。searchはProject名・ProjectId・ConversationId・時刻・speaker・snippetを表示する。

存在しないProject/Conversation、曖昧な名前、無効な範囲指定は明示的なエラーにする。不正な数値や引数不足はpicocliのusage errorを使う。Store等からの実行例外はShellで短いエラーへ変換し、commandからstack traceを表示しない。

## 3. `/history` alias

HistoryCommand.callとShowCommand.callは同じHistoryCommand.showへ委譲する。既定値はHistoryShellService.DEFAULT_LAST=50。対象解決、取得、formatter、秘匿、件数制限がすべて共通で、`/history`単体はhelpではない。

## 4. Project scope

HistoryShellServiceがShell選択先をsnapshotし、ProjectRegistryの一覧からProjectIdを解決する。read中にプロジェクトを登録・切替しない。list/showはそのProjectIdの既存ログのみ読み取り、full ConversationIdを指定した場合も所属が不一致ならエラーにする。別プロジェクトの指定は `--project` で明示する。

## 5. Cross-project search

新しい検索enum・保存形式は作らず、既存HistorySearchRequestを使う。既定CURRENT_PROJECT_PREFERRED、`--current`はCURRENT_PROJECT_ONLY、`--all`はALL_PROJECTS。既存の関連度・fallback条件・件数予算を維持する。

searchForShellは共通検索処理へ委譲するが、Agent向けの検索完了イベントを発行しない。Shell履歴操作はイベント保存や会話追記を行わない。ProjectHistoryRetrievalのscanは逐次読み取りと上位候補だけのPriorityQueueに変更し、全messageを保持しない。秘匿はsnippet切り出し前に行い、巨大tokenの途中だけがsnippetに漏れないようにする。

## 6. 表示制御・大規模履歴

- show: 既定50件。`--last`は1〜10,000。最新N件だけを保持し、表示時は古い順に並べる。同時刻は保存順を維持する。
- `--all`: 日付ファイル順・保存順に逐次出力。全messageのListを作らない。
- list: 既定50件、上限1,000件。`--offset`で次のページを指定する。本文は保持せず会話ごとの件数と最終日時を集計する。
- search: 既定10、指定範囲1〜50。既存の現在Project最大8件・他Project合計最大3件という追加上限を維持するため、`--limit 20`が必ず20件を返すわけではない。
- 本文: 秘匿後に最大2,000コードポイント。長いpayloadは `(truncated)` を表示する。検索snippetは既存APIの500文字上限も適用される。
- 制御文字: 改行・タブを除く制御文字と方向制御文字を除き、端末へのescape制御を防ぐ。保存データは変更しない。

## 7. AgentRunとの関係

ReiApplication.executeInterruptiblyのhistory分岐でpicocliを直接実行する。Agent executorやUserInterventionQueueに投入せず、AgentRunのキャンセル監視・完了読み上げも開始しない。A/BのActiveRunが存在しても件数は変化しない。ShellがBなら、AgentRunScopeがAでも履歴の対象はBになる。

## 8. TDDと検証

Storeの直近・一覧API、formatter/秘匿、コマンド、検索の逐次読み取りをそれぞれRED→GREENの順に実装した。競合状態は制御可能なexecutorとscopeを使って作成し、Thread.sleepには依存しない。

- HistoryStoreReadTest: 最新N件・順序・件数/最終日時・Project分離・空一覧・全role・逐次取得。
- HistoryFormatterTest: secret/Authorizationの秘匿、短縮前の秘匿、巨大payload、制御文字。
- HistoryShellCommandTest: alias同一性、既定50件、指定会話、別Project、list paging、show all、全role/介入、3検索scope、provenance、limit、usage error、曖昧Project、ActiveRun不変、Shell B/Agent A、検索の非追記・逐次読み取り。
- ReiApplicationMultilineInputTest: history/show/searchがexecutorへ入らずcontrol commandとして直接実行される。
- ProjectHistoryRetrievalTest: read API変更に合わせfakeを更新し、既存の順位・fallback・budget・イベント・所有権を検証。

関連テスト80件成功。全体テスト **1,452件、失敗0・エラー0・スキップ0、BUILD SUCCESS**。会話永続化、ProjectId、プロジェクト切替、AgentRun、UserIntervention、ストリーミング、キャンセル、タイムアウト、Tool、Shell通知の既存回帰テストも通過した。

```powershell
.\mvnw.cmd -o '-Dmaven.repo.local=C:\Users\mikoto\.m2\repository' '-Drei.data-dir=F:\project\rei\target\test-rei-data' test
```

ログ: `target/history-focused-final.log`, `target/history-full-test.log`。

全体テスト後、target内の一時POMで実行用JARを別出力先へビルドした（テストの再実行のみ省略、BUILD SUCCESS）。全701 classが検証時のclassと一致することと、実行用manifestを確認した。`target/rei-0.0.1-SNAPSHOT.jar`へコピーし、SHA-256一致を確認済み。ログは `target/history-jar.log`。

## 9. 主要変更ファイル

- `ui/shell/HistoryCommand.java`: picocli構文、alias、端末出力。
- `conversation/HistoryShellService.java`: read-onlyなShell所有権境界とリクエスト処理。
- `conversation/HistoryFormatter.java`: 表示・時刻・短縮・秘匿。
- `conversation/ConversationLogStore.java`: 逐次読み取り、一覧metadata、直近N件。
- `conversation/ConversationHistorySearchService.java`, `ProjectHistoryRetrieval.java`: Shell検索の非追記と候補保持数制限。
- `event/CredentialRedactor.java`, `AgentEventFactory.java`, `ToolEventCallbackDecorator.java`: 既存秘匿処理の共通化。
- `ReiApplication.java`, `ui/shell/RootCommand.java`: control command登録と端末Writer接続。
- 上記テスト、README、本報告書。

## 10. コミット

`feat: add conversation history shell commands`。hashは最終報告に記載する。

## 11. 残課題・範囲

新規保存形式・会話切替コマンド・検索index・対話型pagerは追加しない。JSONL走査時間はログサイズに比例し、一覧の集計metadataは会話数に比例する。巨大な一行のJSON自体は一行分を読み込む。tool/systemは永続ログに存在する内容を表示し、記録されていない過去payloadを生成しない。実Windows端末での手動表示確認は未実施。
