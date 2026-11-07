# テスト実行証跡

## 実行環境

- Windows / OpenJDK 25 / Maven Wrapper
- 実行日: 2026-11-07

## ビルド

| コマンド | 結果 |
|---|---|
| `mvnw.cmd -B -q -DskipTests compile` | 成功（exit 0） |
| `mvnw.cmd -B -q -DskipTests test-compile` | 成功（exit 0） |

## 対象テスト

### 既定プロファイル

`mvnw.cmd -B -q -Dtest='ListFilePathRecoveryTest,AgentEventChatModelTest,AgentEventClientRoutingTest,WorkingSetAdvisorTest,WorkingSetTest,RuntimeContextAdvisorTest,ContextAssemblerTest,ExhaustedResumeRecoveryTest' -DfailIfNoTests=false test`

- exit 0
- 保存レポートで確認できた件数: `ListFilePathRecoveryTest` 1件、`ExhaustedResumeRecoveryTest` 100件、`AgentEventChatModelTest`、`WorkingSetAdvisorTest`、`RuntimeContextAdvisorTest` いずれも failures/errors 0
- 注意: `ContextAssemblerTest` と `WorkingSetTest` は `@Tag("integration")` のため既定プロファイル（`test.excludedGroups=integration | e2e | live`）では実行されない。この実行ではレポートが更新されなかった。

### full プロファイル

`mvnw.cmd -B -q -Pfull -Dtest='ContextAssemblerTest,WorkingSetTest,AgentEventClientRoutingTest' -DfailIfNoTests=false test`

- exit 0
- `ContextAssemblerTest`: 17件、failures 0、errors 0、skipped 0
- `WorkingSetTest`: 20件、failures 0、errors 0、skipped 0
- `AgentEventClientRoutingTest`: 実行（`@Tag("integration")`）

## 確認できたこと

- `ListFilePathRecoveryTest#projectPathRejectionReachesModelAndCorrectedCallSucceedsInSameRun`: `listFile` の拒否が `PROJECT_PATH_EXCLUDED` としてモデルへ届き、同一 Run 内で修正呼び出しが成功する。
- `ExhaustedResumeRecoveryTest#durableReviewRecoveryRequiresCommitForTheExactPendingWorkId(String)[1][2]`: `readReviewWorkItem` と `readNextReviewWorkItem` の両方で、保存・復元後の読み取りキーが維持され、別引数の読み取りだけがブロックされる。
- `ContextAssemblerTest#workingSetChangesOnlyTheTailAfterCompleteToolExchanges(boolean)[1][2]`: Working Set の変更が末尾のみに現れ、元の raw Prompt は変更されない。
- `WorkingSetTest#promptOrderIsStableWhileRecencyStillControlsEviction`: 表示順がパス昇順で安定し、LRU の追い出しは維持される。
- `RuntimeContextAdvisorTest`: `Request start time` が固定され、`getCurrentTime` へ誘導する文言が入る。

## 当初の対象テストの限界

- 保存レポートは実行時点の観測であり、現在のプロセス状態を独立検証したものではない。
- 全 suite（CI の `full-test.ps1`）は実行していない。
- この対象テストでは実 Provider・実 SSE クライアント・実 Windows Terminal での確認は未実施。実 Provider での追加検証は下記に記録する。

## Rei 本体からの実 Provider 検証

- 実行日: 2026-11-07（JST）
- 対象: `main` の [`7f32022b`](https://github.com/mikoto2000/rei/commit/7f32022bf56b01ab29e12c243bb6c9e20c3bd610) からビルドした Rei 本体。Windows / OpenJDK 25 で `mvnw.cmd -o -B -q -Dmaven.test.skip=true package` を実行した。
- 接続先: Rei に設定されている TensorFold / DeepSeek-V4.1-Flash-1017 の OpenAI 互換 `/v1/chat/completions`。
- 観測方法: 分離したデータ領域と合成ファイル `probe-ws-violet.txt` を使い、起動した Rei のチャット API に依頼した。内蔵キャプチャを開始できなかったため、ローカル HTTP 中継で Rei がシリアライズした JSON を観測し、本文を変更せず同じ Provider に転送した。

| ケース | 実リクエストの末尾 | 応答と Run の結果 |
|---|---|---|
| M-2 通常時 | `user → system`。末尾の `system` に `## Current Working Set` と検証用ファイル名が含まれていた。 | 1 リクエストで、ツールを呼ばず期待したファイル名を返した。Run は `COMPLETED`。 |
| L-4 ツール実行後 | `assistant → tool → system`。初回の応答で `readMultiFile` が要求され、次のリクエストでは同じ呼び出し ID のツール結果に続く `system` に、読み取り後の Working Set と検証用ファイル名が含まれていた。 | Rei が実際にファイルを読み、2 回目のリクエストへの応答で期待したファイル内容を返した。Run は `COMPLETED`。 |

両ケースの最終応答で HTTP 200、SSE の `finish_reason: stop`、`[DONE]` を確認した。検証範囲は上記の接続先・モデルと Rei のチャット経路。検証用の Rei を停止し、一時 worktree を削除した。 [取り込み PR #434](https://github.com/mikoto2000/rei/pull/434) · [証跡記録 PR #487](https://github.com/mikoto2000/rei/pull/487)

## L-5 の回帰テスト

追加した `ContextAssemblerTest#takesOneWorkingSetSnapshotPerAssembly` は、取得するたびに異なるリビジョンを返す Working Set を使う。修正前は最初の組み立てで期待したリビジョン1に対してリビジョン4が返り、テストが失敗した。修正後は初回がリビジョン1、次回がリビジョン2となり、各組み立てでの取得が1回であることを確認した。 [修正 PR #499](https://github.com/mikoto2000/rei/pull/499)

Windows / OpenJDK 25 で Maven Wrapper の `full` プロファイルを使い、以下の対象50件が通過した。失敗・エラー・スキップは0件だった。

| テスト | 通過件数 |
|---|---:|
| `ContextAssemblerTest` | 18 |
| `DeliveryPolicyTest` | 9 |
| `WorkingSetAdvisorTest` | 3 |
| `WorkingSetTest` | 20 |

GitHub CI では初回の Windows 回帰 B で既存の `BackgroundProcessManagerTest#spawnReturnsImmediatelyAndCapturesOutput` が標準エラーのアサーションで失敗した。失敗ジョブの再実行で通過し、Windows 回帰 A・B、静的解析、集約チェックの成功を確認してからマージした。 [Windows 回帰の実行結果](https://github.com/mikoto2000/rei/actions/runs/42364944855) · [PR の全チェック](https://github.com/mikoto2000/rei/pull/499/checks)

## L-1 の回帰テスト

修正コミット `aecf8388` で、正常項目と除外項目が混在しても、正常な結果と除外項目の `PROJECT_PATH_EXCLUDED`、再試行の案内が返ることを確認した。 [修正 PR #505](https://github.com/mikoto2000/rei/pull/505)

| 追加したテスト | 確認内容 |
|---|---|
| `ReadingSecurityTest#excludedFileHasRecoveryAlongsideSuccessfulRead` | `.env` と `../outside.txt` の各ケースで、正常なファイルの読み取り結果が残る。 |
| `ReadingSecurityTest#excludedSearchInputsKeepResultsFromOtherQueries` | `grepMultiQuery` と `searchAndRead` は正常なクエリの結果を返す。`searchAndRead` は除外された優先パスのエラーも返す。 |
| `PagedFileSearchTest#excludedQueriesKeepSuccessfulPagesAndOriginalQueryIndexes` | 除外クエリが混在してもページを継続でき、一致結果と除外クエリの番号が入力時の番号と一致する。 |

Windows / OpenJDK 25 で Maven Wrapper の `full` プロファイルを使い、追加分を含む検索・読み取り関連の147件が通過した。失敗・エラー・スキップは0件だった。GitHub CI でも Windows 回帰 A・B、静的解析、集約チェックがすべて通過した。 [PR の全チェック](https://github.com/mikoto2000/rei/pull/505/checks)

## L-2 の既存テスト

修正コミット `7ee43791` で、Windows / OpenJDK 25 上の Maven Wrapper の `full` プロファイルを使い、以下の既存テスト29件が通過した。失敗・エラー・スキップは0件だった。 [修正 PR #516](https://github.com/mikoto2000/rei/pull/516)

| テスト | 通過件数 |
|---|---:|
| `AgentEventChatModelTest` | 17 |
| `AgentEventChatModelTimingTest` | 10 |
| `AgentEventClientRoutingTest` | 1 |
| `TimingOverheadProbeTest` | 1 |

GitHub CI でも Windows 回帰 A・B、静的解析、集約チェックがすべて通過した。 [PR の全チェック](https://github.com/mikoto2000/rei/pull/516/checks)

## M-1 と L-3 の検証

`main` の `0fe6d217` で、Windows / OpenJDK 25 上の Maven Wrapper の `full` プロファイルを使い、`AgentEventChatModelTimingTest#streamingTimingStaysWithTheInvocationAcrossSubscriptionAndSignalScopes` の1件が通過した。購読や応答処理が別スレッドや別 Run のスコープで実行されても、呼び出し元の Run に Span と応答マイルストーンが記録され、別 Run には記録されないことを確認した。 [回帰テスト](https://github.com/mikoto2000/rei/blob/0fe6d2175063f1d20a8fca834a1cab8e417a2ea0/src/test/java/dev/mikoto2000/rei/llm/AgentEventChatModelTimingTest.java#L116) · [取り込み PR #434 の CI](https://github.com/mikoto2000/rei/pull/434/checks)

L-3 はコードで確認した。`call` と `stream` は解決済みの `runId` を唯一の `measurement` メソッドに渡す。このメソッド内に `owner(prompt)` の再呼び出しはない。 [計測メソッド](https://github.com/mikoto2000/rei/blob/0fe6d2175063f1d20a8fca834a1cab8e417a2ea0/src/main/java/dev/mikoto2000/rei/llm/AgentEventChatModel.java#L205) · [修正 PR #417](https://github.com/mikoto2000/rei/pull/417)

## L-6 の回帰テスト

同じコミットの CLI モジュールで、`RunEventOutputTest#llmFailureIsImmediateWithoutRepeatingTheThinkingHeader` の2ケースが通過した。失敗通知が即時表示され、通知の後に同じ思考ストリームの delta が追加される場合と、すぐに完了する場合の両方で、見出しは1回だけ出力された。 [CLI 回帰テスト](https://github.com/mikoto2000/rei/blob/0fe6d2175063f1d20a8fca834a1cab8e417a2ea0/terminal/cli/src/test/java/dev/mikoto2000/rei/cli/RunEventOutputTest.java#L15) · [修正 PR #417](https://github.com/mikoto2000/rei/pull/417)

## L-7 の回帰テスト

同じコミットで Maven Wrapper の `full` プロファイルを使い、`DurableReviewProtocolTest#sizesNewClaimUsingTheAttemptIdReturnedToTheModel` の1件が通過した。実際の attempt ID が仮の ID より多くのトークンを消費する条件でも、初回の読み取りで予算内のページが返り、再読み取りで同じページが返ることを確認した。 [回帰テスト](https://github.com/mikoto2000/rei/blob/0fe6d2175063f1d20a8fca834a1cab8e417a2ea0/src/test/java/dev/mikoto2000/rei/review/DurableReviewProtocolTest.java#L24) · [修正 PR #418 の CI](https://github.com/mikoto2000/rei/pull/418/checks)

## M-1 のキャンセル判定の回帰テスト

停止要求を受理した後、キャンセル通知を処理する前に別スレッドで購読を解除し、Timing Span の状態を確認した。修正前の `main`（`f5e420f7`）では対象 Run を停止する2ケースが失敗した。修正コミット `19576d73` では、解決済みの Run ID で停止要求を照会し、3ケースすべてが通過した。 [修正 PR #525](https://github.com/mikoto2000/rei/pull/525) · [回帰テスト](https://github.com/mikoto2000/rei/blob/19576d739cd3443e176280fe5a0733e9773e45d3/src/test/java/dev/mikoto2000/rei/llm/AgentEventChatModelTimingTest.java#L262)

| 停止要求の対象 | プロンプト内の実行コンテキスト | 修正前 | 修正後 |
|---|---|---|---|
| 購読を所有する Run | なし | `DISCONNECTED`（失敗） | `CANCELLED`（通過） |
| 購読を所有する Run | あり。キャンセル状態は通知前 | `DISCONNECTED`（失敗） | `CANCELLED`（通過） |
| 別の Run | なし | `DISCONNECTED`（通過） | `DISCONNECTED`（通過） |

Windows / OpenJDK 25 で Maven Wrapper の `full` プロファイルを使い、追加した3ケースを含む関連テストとアーキテクチャ検査の計53件が通過した。失敗・エラー・スキップは0件だった。修正 PR の Windows 回帰 A・B、静的解析、集約チェックもすべて通過した。 [PR #525 の全チェック](https://github.com/mikoto2000/rei/pull/525/checks)
