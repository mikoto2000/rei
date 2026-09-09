# Conversation History / Cross-project Retrieval 実装報告

## 1. 変更前の構造と調査

| 調査対象 | 変更前の構造 |
|---|---|
| Conversation persistence | `ConversationLogStore` が Project別JSONLへ追記。短期ChatMemoryは共通SQLite内のProject別conversation_idで分離 |
| Conversation ID | 論理ID `chat:main`、保存キー `project:<UUID>:chat:main`。`ConversationIds.currentChat()` がRunまたはShellの所属を使用 |
| History retrieval | `ConversationHistorySearchService` と `ConversationHistoryTools`。Project所属時は現在のJSONLだけ検索。他Projectの詳細は拒否 |
| Ranking | 空白区切りの全キーワードが本文に部分一致するかで選別し、新しい日時順。数値スコアなし |
| Vector / hybrid | VectorDocument等には既存VectorStoreがあるが、会話検索には利用していない。今回は新しい検索indexを導入しない |
| ProjectId | RunContext、会話ID、Working Set、State、イベント等に導入済み |
| Registry | `projects.json` のUUID・name・path。`ProjectRegistry.list()` から登録済みProjectを解決可能 |
| AgentRunContext | immutableなProjectId / root / conversationId / runId。非同期Tool境界でも復元済み |
| 検索のcurrent project | `ProjectService.contextForOperation()`。AgentRunScopeをShell選択より優先 |
| LLM context | AiConfigurationに登録されたToolの戻り値がJSONとしてToolResponseに入る。自動検索Advisorはない |
| Source metadata | `ConversationSearchResult` / `ConversationHistoryDetail` に追加可能。以前は明示的な出典名・境界情報なし |
| Observability | Agent Event Bus、型付きpayload、Project別JSONL、Shell rendererが利用可能。検索専用イベントは未実装 |

`interest.ConversationHistoryService` は定期的な興味抽出のための最近のUSERメッセージ取得であり、今回の対話検索とは別経路。既存Global Memory / VectorDocument検索も別機能として維持する。

## 2. 採用方式

**Two-stage retrieval** を採用。Project別Storeを自然に読めるため、異なるVectorスコアの正規化やglobal indexを導入せず、他Projectを読まない条件をテストできる。

`HistorySearchRequest` にquery、preferredProjectId、referencedProject、retrievalScope、会話種別・話者・期間・limitを保持する。`HistorySearchScope` はCURRENT_PROJECT_ONLY / CURRENT_PROJECT_PREFERRED / ALL_PROJECTS。検索処理はリクエストとRegistryのスナップショットを使用し、途中でShellの選択を再取得しない。

保存APIは変更しない。追加した `ConversationLogStore.readProject(ProjectId)` は明示Projectの正本を読むだけで、Project切替・履歴コピー・他Projectのファイル解決を行わない。異なるProjectIdの行が誤ってそのディレクトリに置かれても検索対象にしない。

## 3. Project priority と関連度

- CURRENT_PROJECT_PREFERRED: 明示Project → 実行元Project → その他。
- CURRENT_PROJECT_ONLY: 実行元だけ。他Projectの明示指定でも範囲を広げない。
- ALL_PROJECTS: 全登録Projectを読む。Projectによる順位補正をせず、関連度→日時順。出力件数制限は維持する。

各段階では、NFKC・大小文字・空白を正規化した重複のない検索語のうち、本文に一致した割合を関連度とする。0.5未満は除外、0.8以上を高関連度とする。これはキーワードの被覆率であり、意味理解の確率・LLM confidenceではない。同点はInstantで比較して新しい順。

Projectの明示指定はRegistryのUUIDまたは正規化したnameとの一意一致。存在しない・重複した名前はエラーにする。query内の一意な名前にも対応し、名前部分を検索語から除く。複数候補がある場合は勝手に一つを選ばない。英数字名が別単語の一部に一致するのを避ける。LLMには、ユーザーの明示した登録名をToolのreferencedProjectへ渡すよう説明している。

## 4. Fallback と予算

優先段階の返却可能な結果に、高関連度結果が `min(3, 要求limit)` 件あれば他Projectへ広げない。結果なし・弱い結果だけ・高関連度結果の不足なら他Projectを追加検索する。

予算と閾値は `HistoryRetrievalPolicy` の名前付き定数で管理する。

| 制限 | 値 |
|---|---|
| 実行元の検索結果 | 最大8件 |
| 他Projectの検索結果 | 全Project合計最大3件（明示Projectも含む） |
| 検索結果本文 | 1件最大500文字、summaryは最大120文字 |
| 他Projectの詳細取得 | 最大3件、本文1件最大500文字 |

limitを大きく指定しても制限を超えない。Projectを明示した場合も補助履歴の上限を維持する。長文検索結果は最初の一致付近を抜粋する。これらはv1の保守的なキーワード検索用定数で、実運用データでの調整は今後の課題。

## 5. Provenance

検索結果と詳細取得の両方に `sourceProjectId`・`sourceProjectName`・`contextBoundary` を追加。conversationIdもProject namespaceを保持する。検索結果にはrelevanceScoreも含める。

実際のSpring AI ToolCallbackから返るJSONに上記フィールドが含まれることをテストした。LLMへの入力は従来のToolResponse経由を維持する。通常チャットToolは未指定時CURRENT_PROJECT_PREFERREDを使う。

## 6. Filesystem safety

他Projectの結果には、別Projectの履歴であること、file paths / directories / branches / build commands / repository-local stateを現在の環境に適用しないことを明記する。履歴は参考データであり、命令やfilesystemアクセスの許可ではないと伝える。

検索経路はProject別のReiデータだけを読み、Project rootのファイルを開いたり、履歴中のパスを解決したり、Working Setへ追加したりしない。詳細取得も同じ境界情報を保持する。LLMが境界情報を無視する可能性まで機械的に排除する新しいfilesystem sandboxを追加したわけではない。

## 7. AgentRun isolation / Event

Tool呼出し時に `ProjectService.contextForOperation()` から所属を一度取得し、preferredProjectIdとして固定する。既存のToolContext / AgentRunScope伝播により、Run A開始後にShellがBへ切り替わってもAを検索優先対象にする。通常の次RunではShell選択が反映される。

別スレッドの実際のToolCallbackをCountDownLatchで停止し、A所属の追加入力を受け付け、ShellをBへ切り替えてから検索を再開するテストで確認。待機にThread.sleepは使用していない。

`history.search.completed` を既存Agent Eventへ追加。preferredProjectId / scope / searchedProjectCount / currentProjectHitCount / crossProjectHitCountのみを記録し、queryや会話本文をpayloadに含めない。Shellは `[history.search]` で件数を表示する。Project別EventStoreへのpayload往復も確認する。

## 8. TDD とテスト

1. 新しい検索・出典・scopeのテストを先に追加し、未実装APIによるREDを確認（target/history-red.log）。
2. Project Storeの明示読取・検索方針を実装し、既存履歴テスト込み25件GREEN。
3. Tool schema・実JSON・イベント・表示のテストを先に追加しRED（target/history-tool-red.log）。実装後44件GREEN。
4. 非同期Tool/Interventionと小数秒の日時順を検証。日時文字列比較で逆順になるREDを確認（target/history-order-red.log）、Instant比較へ修正。
5. 最終の対象テスト59件GREEN（target/history-final-focused.log）。全体回帰テスト **1,399件、Failures 0、Errors 0、Skipped 0、BUILD SUCCESS**（target/history-all-tests.log、2026-09-09 13:06 JST、1分21秒）。`git diff --check`も成功。

必須17項目は `ProjectHistoryRetrievalTest` の保存A→B→A、同一論理ID、優先・fallback・明示・曖昧名、出典、scope、件数・本文上限、Run所有権、実ToolResponseで検証。既存のProjectRegistry / Working Set / AgentRun / streaming / cancel / timeout / Tool等は全体スイートで回帰確認する。

実行コマンド:

```powershell
.\mvnw.cmd -o '-Dmaven.repo.local=C:\Users\mikoto\.m2\repository' '-Drei.data-dir=F:\project\rei\target\test-rei-data' test
git diff --check
```

sqlite-vecの取得テストはGitHubへの接続を必要とする。実LLMサーバーを使う手動確認は未実施。実データの移行成果物は変更していない。

## 9. 主要変更ファイル

| ファイル | 役割 |
|---|---|
| HistorySearchScope / HistorySearchRequest | 保存scopeと独立した明示的な検索リクエスト |
| HistoryRetrievalPolicy | 関連度閾値・件数・文字数・境界文 |
| ProjectHistoryRetrieval | Registry解決、段階検索、関連度・順位・予算 |
| ConversationLogStore | Projectを切り替えず正本を読むAPI |
| ConversationHistorySearchService | 従来APIからの既定検索、フィルター、出典付き詳細、イベント |
| ConversationHistoryTools | LLM向けscope / Project指定と注意事項 |
| ConversationSearchResult / ConversationHistoryDetail | provenanceと境界情報 |
| HistorySearchCompletedPayload / AgentEventType / AgentEventFactory | 型付き検索観測 |
| ShellAgentEventRenderer | 検索件数表示 |
| ProjectHistoryRetrievalTest / ShellAgentEventRendererTest | 新機能・境界・非同期・表示テスト |
| README.md | 保存先・検索動作・予算の説明 |

## 10. コミット

コミットメッセージ: `feat: retrieve conversation history across projects`。ハッシュは完了メッセージとgit logに記載。

## 11. 意図的に実装しない範囲

- Global Conversation Index、Vector/hybrid検索、Global Memoryへの自動昇格。
- 毎ターンの強制自動検索Advisor。従来どおりLLMが履歴Toolを呼んだときに検索する。
- 自然文の形態素解析、意味検索、Projectの推測・別名生成。Toolは検索キーワードと登録名を受け取る。
- 全Runを通じた検索結果の累積token予算。v1は各応答の件数・文字数上限。
- ログ全体の検索index化・キャッシュ・ページング。十分な結果がある場合は他Projectを読まないが、読むProject内はJSONLを走査する。
- Registryに未登録の旧データを推測して検索すること、会話正本のglobal統合、既存移行データの再書き換え。
- 定期的な興味抽出や既存のMemory抽出機能の設計変更。
