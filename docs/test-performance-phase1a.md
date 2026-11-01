# 自動テスト高速化 Phase 1-A

## 対象・環境・測定方法

2026-11-01（Asia/Tokyo）。開始時に最新mainをfetchし、
`a26f7e56a349b873a14798e64f502d4d6ba74cfb` を変更前の固定点にした。
専用ブランチ `codex/test-performance-phase1a`、専用worktreeを使用し、元の未コミット変更や
他のworktreeは変更していない。[Phase 0](test-performance-analysis.md)は旧コミットの測定なので、
本書では最新mainのfullとfastを取り直した。

環境はWindows 11 Pro 10.0.26200、Intel Core i9-12900（16コア/24論理CPU）、
OS可視メモリ約127.75 GiB、OpenJDK 25（25+36-3489）、Maven Wrapper 3.9.14、PowerShell 7。
Spring Boot 4.1.1、JUnit Jupiter 6.0.3、Surefire 3.5.6、1 fork / reuseForks=true、
Jupiter並列無効を維持した。POM・fork・並列設定は変更していない。

既存の `measure-tests.ps1` と `test-performance-report.ps1` を使用する。
Mavenビルドを並行して実行せず、各run終了後に次を開始する。常駐アプリは停止していない。
変更前fullで初回コンパイルとネイティブ拡張の準備を済ませ、fastはコンパイル済みで測定する。
変更後も関連テストでコンパイルした後にfastを測定する。Mavenキャッシュ保存先は共通で、
各run間にcleanしない。ソース調査・短いPowerShell契約テスト以外の並列負荷は加えていない。

```powershell
# PowerShell 7。ラベルは毎回未使用のものにする。
./scripts/measure-tests.ps1 -Label before-fast1 -Repository '<dependency-cache>' `
  -MavenArguments @('test','-B')
./scripts/measure-tests.ps1 -Label after-integration -Repository '<dependency-cache>' `
  -MavenArguments @('test','-Pintegration-only','-B')
./scripts/measure-tests.ps1 -Label after-full -Repository '<dependency-cache>' `
  -MavenArguments @('test','-Pfull','-B')
```

wall-clockはMaven Wrapper起動〜終了のStopwatch実測で、XMLコピー・集計時間を含めない。
XML suite秒数の合計はwall-clockと別指標。失敗runは短縮率の計算に使わない。
変更前fullの1,014.999秒は初回コンパイル/準備を含むため、変更後fullとの差を改善率には使わない。
fullは既存定義どおりliveを除外し、e2eも実行する。live/e2eの定義・既存の除外条件は維持した。

## 30秒問題の診断とセキュリティ修正

`2130706433`はJDKのdecimal IPv4解釈では127.0.0.1だが、既存の
`PublicNetworkPolicy.literal`はNettyのドット4要素IPv4判定に合致しないためnullを返す。
従来はこの形式を通常のDNS名として許可し、`SafeHttpFetcher.exchange`のresolverへ渡していた。

診断用のLogback設定（Netty DNS DEBUG、Reactor transport TRACE）をtarget配下だけに置き、
5つの拒否ケースを同じ条件で3回測定した。問題ケースで
`DefaultDnsQuestion(2130706433. IN A)`と`IN AAAA`の実DNS問い合わせ、数秒間隔の再試行を観測した。
DNSの応答失敗・再試行が待ちを作り、既存の再試行/全体deadlineの経路へ入っていた。
TCP connect/readのtimeoutを短縮する問題ではない。診断ログ中のDNSサーバー・ポート・
チャネルID等は本書やGitに転記しない。

修正前の当該caseは26.912 / 30.004 / 26.964秒、中央値26.964秒。
Phase 0の3回約30秒と異なるのは、固定sleepではなく外部DNSの応答・再試行に依存するため。
旧テストはPaperExceptionなら成功するので、明示的拒否ではなくDNS失敗やtimeoutでも通り得た。

共通の`PublicNetworkPolicy.validateSyntax`に、曖昧な数値IPv4表記の拒否を追加した。
単一整数・2/3要素・16進・先頭ゼロ・数値ホストの末尾ドット・範囲外の数値列は、
DNSや接続に進めず`URL_NOT_ALLOWED`となる。公開IPを表す旧式の表記も曖昧さを理由に拒否し、
正規のドット4要素decimal IPv4への書き換えを求める。
通常のDNS名（`0xface.example`を含む）、大文字/末尾ドットのDNS名、正規の公開IPv4/IPv6は維持する。

IPv6 loopback・IPv4-mapped privateアドレス・private/link-local/reservedアドレスの既存拒否を維持した。
DNS全回答の検証、承認済みアドレスへのpinning、実peer照合、各リダイレクトの再検証、
timeout/deadline/retryは変更していない。正規化して許可範囲を広げる変更は行っていない。

40件の軽量回帰を追加した。曖昧/禁止URI22件、正当な公開URI6件、共通fetcherのredirect11件、
Paper固有redirect1件。redirectテストはtransportだけをfakeにし、実際のredirectループが
2回目のexchange前に拒否することを検証する。既存5件のtransport拒否テストは削除せず、
エラーコードと接続前の`validateUri`拒否も検証するよう強化した。
新しいテストは旧実装で88件中13件失敗（failure、error/skipなし）、最小修正後は関連6クラス118件すべて成功した。

参考: [JDK 25 Inet4Address](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/Inet4Address.html)、
[Netty NetUtil](https://netty.io/4.2/api/io/netty/util/NetUtil.html)。
JDKの数値IPv4表記とPOSIXの8進/16進解釈は一致しないため、複数の解釈を許可する方針にはしない。

| 対象caseのXML秒数 | 1回目 | 2回目 | 3回目 | 中央値 |
|---|---:|---:|---:|---:|
| 変更前 | 26.912 | 30.004 | 26.964 | 26.964 |
| 変更後 | 0.002 | 0.004 | 0.007 | 0.004 |

5件の同じParameterizedTest、同じ診断ログ設定、同じ環境で測定し、各runは5件成功・skipなし。
変更後は数値ホストに対する`DefaultDnsQuestion`が3runとも0件になった。
中央値の差は26.960秒（約99.985%減）。XMLのミリ秒精度の値であり、ゼロ時間とは扱わない。

## 分類変更と判断理由

名前・秒数だけで分類せず、実際に使う境界を確認した。全ケースで実Gitや実DBのfixtureを
使うクラスはクラス単位、軽いfake/純粋ロジックと混在するものはメソッド単位で
既存の `@Tag("integration")` を追加する。テストの削除・assertion/境界/試行数の削減はしない。

36クラスはクラス単位、15クラスはメソッド単位、計51ファイルにタグを追加した。

| テスト（packageはdev.mikoto2000.rei） | 適用範囲 | 根拠 |
|---|---|---|
| `core/SelfPatchReviewGitTest` | クラス全体 | 実Git/worktree・子プロセスを使う契約（全ケース） |
| `core/SelfPatchReviewCancellationTest` | クラス全体 | 実Git/worktree・子プロセスを使う契約（全ケース） |
| `externalagent/IsolatedImplementationServiceTest` | クラス全体 | 実Git/worktree・子プロセスを使う契約（全ケース） |
| `externalagent/ImplementationDelegationIntegrationTest` | クラス全体 | 実Git/worktree・子プロセスを使う契約（全ケース） |
| `durable/SnapshotScaleTest` | クラス全体 | 1万ファイルscaleと実SQLite fixture（全ケース） |
| `durable/GitSnapshotTest` | クラス全体 | 実Git/worktree・子プロセスを使う契約（全ケース） |
| `durable/TaskSnapshotTest` | クラス全体 | 実SQLiteの準備・migration・永続化/再起動または子JVM境界（全ケース） |
| `durable/WorkItemCrashTest` | クラス全体 | 実SQLiteの準備・migration・永続化/再起動または子JVM境界（全ケース） |
| `core/PagedFileSearchTest` | クラス全体 | 実SQLiteの準備・migration・永続化/再起動または子JVM境界（全ケース） |
| `core/TextDiffContractTest` | クラス全体 | 実Git/worktree・子プロセスを使う契約（全ケース） |
| `core/TextDocumentChangeSetTest` | クラス全体 | 実SQLiteの準備・migration・永続化/再起動または子JVM境界（全ケース） |
| `core/ReadingDeliveryTest` | クラス全体 | 実SQLiteの準備・migration・永続化/再起動または子JVM境界（全ケース） |
| `core/ReadingMetricsPersistenceTest` | クラス全体 | 実SQLiteの準備・migration・永続化/再起動または子JVM境界（全ケース） |
| `conversation/ConversationAdmissionStoreTest` | クラス全体 | 実SQLiteの準備・migration・永続化/再起動または子JVM境界（全ケース） |
| `storage/DotMigrationTest` | クラス全体 | 実SQLiteの準備・migration・永続化/再起動または子JVM境界（全ケース） |
| `storage/DurableTaskMigrationTest` | クラス全体 | 実SQLiteの準備・migration・永続化/再起動または子JVM境界（全ケース） |
| `storage/DurableWorkMigrationTest` | クラス全体 | 実SQLiteの準備・migration・永続化/再起動または子JVM境界（全ケース） |
| `review/DurableReviewProtocolTest` | クラス全体 | 実SQLiteの準備・migration・永続化/再起動または子JVM境界（全ケース） |
| `review/ExternalCoverageVerificationTest` | クラス全体 | 実SQLiteの準備・migration・永続化/再起動または子JVM境界（全ケース） |
| `review/RepositoryReviewEntryTest` | クラス全体 | 実SQLiteの準備・migration・永続化/再起動または子JVM境界（全ケース） |
| `review/ReviewAccessRecoveryTest` | クラス全体 | 実SQLiteの準備・migration・永続化/再起動または子JVM境界（全ケース） |
| `review/ReviewContinuationTest` | クラス全体 | 実SQLiteの準備・migration・永続化/再起動または子JVM境界（全ケース） |
| `review/ReviewCoverageLedgerTest` | クラス全体 | 実SQLiteの準備・migration・永続化/再起動または子JVM境界（全ケース） |
| `review/ReviewDeliveryBoundaryTest` | クラス全体 | 実SQLiteの準備・migration・永続化/再起動または子JVM境界（全ケース） |
| `review/ReviewIndexedPageTest` | クラス全体 | 実SQLiteの準備・migration・永続化/再起動または子JVM境界（全ケース） |
| `review/ReviewModelBoundaryTest` | クラス全体 | 実SQLiteの準備・migration・永続化/再起動または子JVM境界（全ケース） |
| `review/ReviewPageBatchTest` | クラス全体 | 実SQLiteの準備・migration・永続化/再起動または子JVM境界（全ケース） |
| `review/ReviewQualityEvaluationTest` | クラス全体 | 実SQLiteの準備・migration・永続化/再起動または子JVM境界（全ケース） |
| `review/ReviewToolsTest` | クラス全体 | 実SQLiteの準備・migration・永続化/再起動または子JVM境界（全ケース） |
| `chatgpt/CredentialProtectionIntegrationTest` | `fileLockSerializesSeparateProcesses` | 混在クラス: 実Git/DB/migration/子プロセスのケースだけ移動。fake・純粋ロジック・少量のファイル検証はfast維持 |
| `checkpoint/CheckpointReconcilerTest` | `readingGitBaselineDoesNotExecuteRepositoryConfiguredFsmonitor` | 混在クラス: 実Git/DB/migration/子プロセスのケースだけ移動。fake・純粋ロジック・少量のファイル検証はfast維持 |
| `core/FileOperationsOptimizationTest` | `directoryLinksCannotAliasTheReadingBoundary` | 混在クラス: 実Git/DB/migration/子プロセスのケースだけ移動。fake・純粋ロジック・少量のファイル検証はfast維持 |
| `core/GitChangeTestImpactTest` | `quotedPathAndHeaderLikeAddedTextCannotRedirectLineEvidence`, `gitCoverageObservesCurrentChangedLinesWithoutExecutingTests`, `collectsStagedUnstagedDeletedAndUntrackedPathsWithoutChangingIndex`, `cleanRepositoryReturnsEmptyObservationAndExcludedPathsAreNotExposed`, `renameIncludesOldAndNewAndTooManyChangesAreRejected`, `nonRepositoryAndNestedRootAreRejected`, `stagedChangeIsRetainedWhenWorktreeMatchesHeadAndIgnoredFilesStayExcluded`, `omittedToolArgumentUsesCapturedProjectAndRetainsReadPermission` | 混在クラス: 実Git/DB/migration/子プロセスのケースだけ移動。fake・純粋ロジック・少量のファイル検証はfast維持 |
| `core/ReadingSecurityTest` | `aValidCursorCannotCrossRunsAndExpirationIsDistinct` | 混在クラス: 実Git/DB/migration/子プロセスのケースだけ移動。fake・純粋ロジック・少量のファイル検証はfast維持 |
| `core/RepositoryMapServiceTest` | `gitInventoryRespectsIgnoreAndDoesNotReturnSourceBodies` | 混在クラス: 実Git/DB/migration/子プロセスのケースだけ移動。fake・純粋ロジック・少量のファイル検証はfast維持 |
| `core/SearchPreparationReuseTest` | `pagedSearchPreparesFiltersOnceForAllFiles` | 混在クラス: 実Git/DB/migration/子プロセスのケースだけ移動。fake・純粋ロジック・少量のファイル検証はfast維持 |
| `core/service/SystemShellServiceTest` | `nativePowershellPreservesQuotedCommandsAndUnicode` | 混在クラス: 実Git/DB/migration/子プロセスのケースだけ移動。fake・純粋ロジック・少量のファイル検証はfast維持 |
| `core/TestReportCollectionDiagnosisTest` | `linkedOutsideDirectoryIsRejectedWithoutReadingItsReport` | 混在クラス: 実Git/DB/migration/子プロセスのケースだけ移動。fake・純粋ロジック・少量のファイル検証はfast維持 |
| `review/ReviewEvidenceValidationTest` | `fabricatedLineCoordinatesCannotBecomeVerifiedFindings`, `windowsCaseAliasesCannotCreateTwoTargetsForOneFile`, `findingsCannotSplitASurrogatePair` | 混在クラス: 実Git/DB/migration/子プロセスのケースだけ移動。fake・純粋ロジック・少量のファイル検証はfast維持 |
| `mcp/McpToolsTest` | クラス全体 | 共有fixtureまたは全ケースが実SQLite/migration・永続化/再起動を使用 |
| `goal/DurableGoalGateTest` | クラス全体 | 共有fixtureまたは全ケースが実SQLite/migration・永続化/再起動を使用 |
| `durable/WorkItemRecoveryTest` | クラス全体 | 共有fixtureまたは全ケースが実SQLite/migration・永続化/再起動を使用 |
| `durable/WorkItemDeliveryTest` | クラス全体 | 共有fixtureまたは全ケースが実SQLite/migration・永続化/再起動を使用 |
| `chatgpt/WindowsPlanAppServerProcessTest` | `nativeProcessHasDeadlineEvenWhenNoRpcIsSent`, `startsNativeRestrictedThreadThenDeletesOwnHome` | 混在クラスの実DBまたは実子プロセス境界だけを移動。既存のopt-in条件を維持 |
| `chatgpt/WslPlanAppServerProcessTest` | `startsRestrictedThreadWithoutTurnThenClosesNativeProcessAndDeletesOwnHome` | 混在クラスの実DBまたは実子プロセス境界だけを移動。既存のopt-in条件を維持 |
| `core/ReadingEfficiencyBenchmarkTest` | `resultCursorAvoidsNativeReadOfTheGapWithIdenticalTokenBudgets` | 混在クラスの実DBまたは実子プロセス境界だけを移動。既存のopt-in条件を維持 |
| `externalagent/ClaudeCodeExtensionsAdapterTest` | `nativeStorageAliasIntoSourceNeverCreatesDirectoriesOrStartsCli` | 混在クラスの実DBまたは実子プロセス境界だけを移動。既存のopt-in条件を維持 |
| `launcher/BackendEndpointTest` | `actualSecondJvmCannotOwnOrMigrateSameStorageAndCrashReleasesLock`, `simultaneousJvmCandidatesLeaveExactlyOneOwner` | 混在クラスの実DBまたは実子プロセス境界だけを移動。既存のopt-in条件を維持 |
| `dot/DotResumeIntegrationTest` | クラス全体 | 共有fixtureが実SQLiteで永続化・再開/再起動を検証（全ケース） |
| `review/LegacyReviewResumeTest` | クラス全体 | 共有fixtureが実SQLiteで永続化・再開/再起動を検証（全ケース） |
| `reflection/ReflectionLessonServiceTest` | クラス全体 | 共有fixtureが実SQLiteで永続化・再開/再起動を検証（全ケース） |

`CommandHelpTest`は全コマンドのhelpをmockで検証し、本体を実行しないためfastに残す。
`SearchPreparationReuseTest`のCountingToolsによる2ケース、`ReadingSecurityTest`の純粋な
ポリシー/正規表現ケース、`GitChangeTestImpactTest`のfakeによる取消ケース、
`ReviewEvidenceValidationTest`の文字コードケース等もfastに残す。
分類のためにfixtureを共有化したり、実DBをmockに置換したりしていない。

## 件数・実測時間・短縮率

### 基準測定時に見つかった既存fastの失敗

最初のfast（357.199秒）は4,412件中2件失敗し、**成功run・短縮率計算には含めない**。
`ChatCommandTest#runRendersMemorySuggestionThroughShellAgentEventRendererWhenAutoTriggerIsTrue` と
`MemoryAutoTriggerTest#chatRendersSuggestionThroughShellAgentEventRendererWhenAutoTriggerEnabled`。
失敗は提案の描画ではなく、直接stdoutが空であることのassertionだった。
対象18件の単独実行でも2件失敗し、既存のWARNログ設定を診断指定すると18件成功した。
Spring起動済みのfullではログがファイルへ設定される一方、単独/fastでは診断ログがstdoutに
流れるというfixture依存だった。

この2クラスだけで@BeforeEachに診断ログをWARNへ設定し、@AfterEachで元に復元する。
表示内容・直接stdoutが空というassertionの条件は維持し、失敗時メッセージだけ補足した。
アプリケーションコードや全テスト共通のlogging設定は変えない。対象18件は通常コマンドで成功。
この共通fixture修正 `b1aec743` を**分類/SSRF修正の前後両方**に適用した上で、
成功する変更前fastを3回取り直した。変更前fullの全識別子は固定点mainの7,170件を使用する。

### 成功runの比較

| Run | Wall秒 | 件数 | skip | suite / 実行suite | suite秒合計 |
|---|---:|---:|---:|---:|---:|
| p1a-before-fast-stable1 | 410.265 | 4412 | 4 | 835 / 832 | 382.089 |
| p1a-before-fast-stable2 | 380.067 | 4412 | 4 | 835 / 832 | 371.063 |
| p1a-before-fast-stable3 | 438.184 | 4412 | 4 | 835 / 832 | 428.285 |
| p1a-after-fast1 | 222.734 | 4253 | 1 | 799 / 798 | 196.297 |
| p1a-after-fast2 | 193.020 | 4253 | 1 | 799 / 798 | 183.577 |
| p1a-after-fast3 | 192.864 | 4253 | 1 | 799 / 798 | 181.608 |
| p1a-after-integration | 990.994 | 2957 | 7 | 487 / 483 | 981.010 |
| p1a-after-full | 957.494 | 7210 | 8 | 1266 / 1261 | 924.272 |

変更前fast中央値 **410.265秒**（範囲380.067〜438.184秒）、変更後中央値 **193.020秒**
（範囲192.864〜222.734秒）。中央値で217.244秒短縮した。

`(410.2649115 - 193.0204793) / 410.2649115 × 100 = 52.952%`。

既存fastからintegrationへ移した識別子は199件（このうち既存skip3件）。
軽量なセキュリティ回帰40件を追加し、fastは4,412→4,253件になった。
移した199件の変更前testcase時間合計は226.699秒だが、共有fixture・実行順・JIT等の影響を
含むので、これをそのままwall-clockの削減量とは見なさない。

fullは変更前wall 1,014.999秒（suite合計877.725秒）、変更後wall 957.494秒
（suite合計924.272秒）だった。前者は初回コンパイル/準備を含み、各1回の測定なので、
**full全体の改善率は算出しない**。Paper単独の改善とfull全体の変動を混同しない。
integration-onlyがfullより長かったことからも、別runのwall-clockは加算できない。
実行順・共有fixture・JIT/OSキャッシュ・常駐負荷等が変動要因の候補だが、内訳は未計測である。

PaperSafetyTestは変更前からintegrationタグ付きなので、通常のfastに30秒ケースは入らない。
したがってfast短縮をPaperSafetyTestの30秒削減に帰属させない。fastの比較は主に分類変更の効果と、
追加した軽量なセキュリティ回帰テストの実行を含む。full/integrationには別にPaperの改善が効く。
変動・キャッシュ・常駐負荷を完全に排除した実験ではなく、単一ホストでの観測である。

## テスト集合とskipの検証

`scripts/check-test-partition.ps1`を拡張し、新しいランナーは作っていない。
識別子はXMLのqualified suite名と正確なtestcase名の組とし、parameter番号/dynamic識別名を保持する。
jqwikのclassnameが単純名でもsuite名を使用する。曖昧な重複識別名は併合せず拒否する。

- `fast ∪ integration-only = full`、`fast ∩ integration-only = ∅`を実行XMLで検証。
- skipも集合に含めるので、skipケースの欠落を成功扱いしない。
- `config/test-expected-skips.json`の識別名・理由と一致するskipだけを許可し、JSONに理由を記録。
- 属性が空ならskipped本文の先頭理由を使用し、JUnit例外クラス名の接頭辞だけを除く。
- failure/error、予期しないskip、理由の変化、各profileの結果の不一致、不正なXML/件数を拒否する。
- 変更前fullの全識別子が変更後fullに残ることも確認。既存成功ケースがskipへ変われば拒否する。
- 許可リストはskipを強制しない。任意の実環境が整ってテストが実行された場合は許容する。
  現在の許可リストはWindowsの基準で、別OSの追加skipを無条件に認めるものではない。

```powershell
./scripts/test-check-test-partition.ps1
./scripts/test-test-performance-report.ps1
./scripts/check-test-partition.ps1 `
  -Fast target/test-performance/after-fast1/xml `
  -Integration target/test-performance/after-integration/xml `
  -Full target/test-performance/after-full/xml `
  -Before target/test-performance/before-full/xml
```

必要なら `-ExpectedSkips <承認済みJSON>` と `-Output <集計JSON>` を指定できる。
生ログ・XML・診断ログ・ローカルパスを含むtiming/arguments JSONはGitに保存しない。

実XMLで **fast 4,253 + integration-only 2,957 = full 7,210** を確認した。
共通の識別子は0件。変更前full 7,170件の欠落は0件、新規追加は40件。
fastの3runすべてについて同じintegration/full/変更前fullと照合し、すべて成功した。
既存成功ケースがskipへ変わったものは0件。

skipはfast 1件、integration-only 7件、full 8件。fullの8件は変更前と識別名・理由が一致した。
REI_TEST_WINDOWS_CODEX未設定の2件、REI_TEST_WSL_CODEX未設定の1件、PlantUML jar未設定の1件、
手動benchmarkのsystem property未設定3件、holiday.csv未設定の1件である。
全識別名・理由は[集計JSON](test-performance-phase1a-summary.json)と
[許可リスト](../config/test-expected-skips.json)を参照。
suiteは混在クラスや0件suiteを含むため加算せず、集合式はtestcase識別子で評価した。

## TDD・検証・残存課題

集合チェッカーは期待skipを含む正常入力からRed→Greenで拡張し、欠落・重複・理由の変化・
失敗/エラー・パラメータ/dynamic名・既存full保持・新規セキュリティテストの追加を契約テストで検証した。
既存の集計契約テストも維持し、両方をCIで実行する。

分類のRed確認では、変更前fastに対象の重い4 suiteの21ケースが残ることを検出した。
分類後は4 suiteがfastから除外され、mockのCommandHelpと混在クラスのfake取消ケースが残ることを確認した。
51ファイルすべてで追加タグ行を除去した内容が`b1aec743`と一致し、テスト本体・assertion・試行数の維持を確認した。
整形後もSSRF関連118件が成功し、上表のfast3回・integration-only・fullはすべてfailure/error 0件。
PowerShellの集合検証・既存集計契約テストも成功した。

実装・分類の測定対象コミットは`d6a33fc16123dd90ad2b37c3cdbf8d61feedb55f`。
レポートと集計JSONの追加はその後で行い、測定対象のJava/POM/profileは変更していない。

変更後fast中央値のrun（p1a-after-fast2）で残った上位10 suite。実測値であり、最適化時の削減見込みではない。

| Suite | 件数 | suite秒 |
|---|---:|---:|
| `dev.mikoto2000.rei.ui.shell.CommandHelpTest` | 1 | 23.202 |
| `dev.mikoto2000.rei.voice.VoiceInputCoordinatorTest` | 26 | 9.391 |
| `dev.mikoto2000.rei.architecture.PackageArchitectureTest` | 1 | 5.223 |
| `dev.mikoto2000.rei.core.configuration.AiConfigurationTest` | 14 | 3.584 |
| `dev.mikoto2000.rei.subagent.ChatGptSubAgentPartitionTest` | 12 | 3.521 |
| `dev.mikoto2000.rei.web.CliCatalogControllerTest` | 4 | 3.373 |
| `dev.mikoto2000.rei.voice.VoiceHealthLifecycleTest` | 4 | 3.290 |
| `dev.mikoto2000.rei.event.EventPageTest` | 3 | 3.016 |
| `dev.mikoto2000.rei.core.TestReportCollectionDiagnosisTest` | 7 | 2.997 |
| `dev.mikoto2000.rei.checkpoint.ResumeOverviewRepositoryTest` | 15 | 2.959 |

変更後fullの上位10 suite。重い永続化・ページング・Git fixtureの契約が残っている。

| Suite | 件数 | suite秒 |
|---|---:|---:|
| `dev.mikoto2000.rei.application.task.TaskSourcePaginationTest` | 2 | 44.324 |
| `dev.mikoto2000.rei.storage.StorageEventMigrationTest` | 16 | 39.288 |
| `dev.mikoto2000.rei.core.SelfPatchReviewGitTest` | 12 | 36.693 |
| `dev.mikoto2000.rei.core.PagedFileSearchTest` | 3 | 28.336 |
| `dev.mikoto2000.rei.externalagent.IsolatedImplementationServiceTest` | 5 | 27.076 |
| `dev.mikoto2000.rei.core.ToolsTest` | 113 | 26.423 |
| `dev.mikoto2000.rei.externalagent.SpecificationDelegationIntegrationTest` | 7 | 23.643 |
| `dev.mikoto2000.rei.checkpoint.PersistentCheckpointServiceTest` | 62 | 20.781 |
| `dev.mikoto2000.rei.storage.StorageMigrationCoordinatorTest` | 27 | 19.246 |
| `dev.mikoto2000.rei.review.ReviewToolsTest` | 19 | 18.256 |

小規模なファイルI/Oや短いfake workerの子JVMプロトコル検証等はfastにも残る。
今回の分類は主要な重い境界を優先しており、fastからすべてのI/Oを排除したという意味ではない。

次の優先順位は以下とする。

1. **Phase 1-B：実DB・Git・大量fixtureの準備最適化を最優先**。
   integrationへ移した契約はfullに残る。準備/本体/後始末を分けて計測し、migration・DB生成・
   worktree作成の重複を、小さなTDD変更で削減する。共有化の前にテスト間の状態分離を確認する。
2. **fastに残る起動費と待機契約を計測する**。
   CommandHelpはmock主体でも23.202秒、VoiceInputCoordinatorは9.391秒。
   JVM/mock初期化、5秒級の取消契約、アーキテクチャ走査等の内訳を分け、
   不要な待ちをfixtureで制御できるか検証する。名称や秒数だけでさらに移動する判断はしない。
3. **Phase 2：Test Impact Analysisはshadow運用から**。
   既存の変更影響候補を記録し、実際のfull結果と照合して取りこぼし率を測る。
   選別実行を既定にせず、PR/main前のfullを継続する。並列化は別実験として扱う。

分類変更は日常のfast待ちを減らしたが、全件回帰のfixture最適化を代替しない。
Phase 1-Bを先行し、Phase 2のshadow観測をその次に進める。
