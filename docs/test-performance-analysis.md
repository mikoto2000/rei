# 自動テストの実行時間計測とボトルネック分析（Phase 0）

## 1. 対象と計測環境

測定日: 2026-11-01、Asia/Tokyo。対象 main:
`01a6d1dd2f6ecc63ccd7224c8265448e8e5b8071`（PR #201）。
開始時に最新mainをfetchしてこのコミットへ固定した。計測中に別作業のPR #202がmain
`92e54f78`へ反映されたが、その変更を混ぜて測定条件を変えていない。本書の数値と実装調査は
上記固定コミットのスナップショットであり、PR #202の性能は未計測。成果物のPRでは最新mainとのCIも確認する。
`codex/test-performance-phase0` の専用 worktree で実行し、元の作業ツリーの未コミット変更と
既存 worktree には触れていない。今回の変更は集計ツール、その契約テスト、CIでの契約テスト実行、
計測レポートと集計データのみ。テスト選別、並列化、fork、DB、テストコードの高速化は実装していない。
全runでJavaソース・POM・Maven Wrapperは対象mainと同一。集計スクリプトの変更は
Stopwatch停止後の後処理であり、テスト実行設定には反映していない。

| 項目 | 条件 |
| --- | --- |
| OS | Windows 11 Pro、10.0.26200、amd64 |
| CPU | Intel Core i9-12900、16コア / 24論理プロセッサ |
| メモリ | OS可視 133,952,812 KiB（約127.75 GiB） |
| JDK | OpenJDK 25、25+36-3489、Oracle Corporation |
| Maven | Wrapper 3.9.14、PowerShell 7 |
| Spring Boot / Framework | 4.1.1 / 7.0.9（effective POM） |
| JUnit Jupiter / Platform | 6.0.3（effective POM） |
| jqwik | 1.9.3 |
| Surefire / Failsafe | 3.5.6 / 3.5.6（後者はpluginManagementのみ、実行登録なし） |
| キャッシュ | 既存Maven依存キャッシュを使用。コンパイル済み、各run間でcleanしない |

ローカルキャッシュの絶対パス、ユーザー設定、環境変数全体、XMLのsystem-properties、
テストの生ログはGitに保存しない。ログとXMLは当該worktreeの無視対象 `target/test-performance/` に保存。
JDKはCIのTemurinと同じメジャーだが配布元が異なる。OS・ディスク・常駐プロセスの影響を含む
ローカル観測であり、CI所要時間の予測値ではない。既存の別Javaプロセスは停止していない。
測定中にソース調査や軽いスクリプト検証も行ったため、完全な無負荷ホストでのベンチマークではない。

## 2. 実行基盤と測定コマンド

日常の標準コマンドは `./mvnw.cmd test`。既定で `integration | e2e | live` を除外する。
全件回帰の標準は `./mvnw.cmd -B -Pfull test` で、CIもこれを用いる。
`full` と `integration` は `live` のみを除外し、`integration-only` は
`integration | e2e` のみを選択する。`live-e2e` はliveだけを選択する。
したがって本書の「全件」は**既存fullプロファイルの自動回帰全件**を意味し、
実環境・実LLM接続用のliveやclient/nativeの別テスト群は含めない。対象を独自にskipしていない。

```powershell
# PowerShell 7。<dependency-cache>は利用環境の既存Maven repository。
# labelをnormal1/normal2/normal3と変え、一つずつ完了を待つ。
./scripts/measure-tests.ps1 -Label phase0-normal1 `
  -Repository '<dependency-cache>' `
  -MavenArguments @('test','-Pfull','-B',
    '-Dorg.slf4j.simpleLogger.showDateTime=true',
    '-Dorg.slf4j.simpleLogger.dateTimeFormat=HH:mm:ss.SSS')

./scripts/test-test-performance-report.ps1
./scripts/test-performance-report.ps1 -Reports @(
  'target/surefire-reports','target/failsafe-reports') `
  -Output target/test-performance/combined -WallSeconds 123.456
```

最後の例は両ディレクトリが存在するときに使う。現構成にはFailsafe出力がないため、
通常はSurefireだけを渡す。コピー先名が `failsafe-reports` の場合のみFailsafeと識別し、
その他はSurefireとして扱う。異なるrunのレポートを同時に渡してはいけない。

- Surefireは `forkCount=1`、`reuseForks=true`、`failIfNoTests=true`。
  Jupiter並列実行は明示的に無効。Mavenの `-T` 指定や永続的並列ビルド設定なし。
  単一Javaモジュールなので、Maven並列ビルドだけでは当該suiteの分散にならない。
- JUnit / jqwikのタグで分類する。Unit/Componentは排他的なJUnitタグではないため、
  ソース棚卸しによる分類候補と、実行されたXML suite数を混同しない。
- CIはWindows、Temurin 25。architecture 3クラスを先行実行し、次にfullを実行するため重複する。
  `.github/ci/full-test.ps1` は1,200秒で所有プロセスツリーを停止し、ログを保持して失敗する。
  CIには専用TEMP/TMPがあり、ローカルは標準TEMP。別jobでCheckstyle / SpotBugsと既存負債のgateを実施。
- 既存 `measure-tests.ps1`、`test-performance-report.ps1`、`test-resource-inventory.ps1`、
  `check-test-partition.ps1`、[前回の分析](test-performance.md)を再利用した。
  前回は約2,600件時点であり、現在の計測値に転用しない。

## 3. 全体の実測時間

| run | 終了日時（JST） | wall秒 | Maven全体 | Surefireフェーズ秒 | XML suite合計秒 | tests / passed / failures / errors / skipped | suites |
| --- | --- | ---: | --- | ---: | ---: | --- | ---: |
| 1 | 2026-11-01T08:46:31+09:00 | 981.168 | 16:18 min | 976.729 | 971.798 | 7168 / 7160 / 0 / 0 / 8 | 1264 |
| 2 | 2026-11-01T09:01:45+09:00 | 864.542 | 14:22 min | 860.760 | 856.657 | 7168 / 7160 / 0 / 0 / 8 | 1264 |
| 3 | 2026-11-01T09:15:54+09:00 | 808.000 | 13:25 min | 804.046 | 799.916 | 7168 / 7160 / 0 / 0 / 8 | 1264 |

3回のwall-clock: **中央値 864.542秒、最小 808.000秒、最大 981.168秒**。以下のランキング・分布は中央値のrun 2を代表値にする。各runは同じコマンド、同じソース、同じキャッシュ保存先で逐次実行。最初の通常権限runはsqlite-vec等の初回準備を含み得るため、完全なcold/warm統制試験ではない。

Failsafeは3回とも未実行（0件・0 suite・XML合計0秒）。時間0はプラグイン起動の実測値ではなく、実行登録がないことを示す。全件のcold compile wall-clockは取得していない（サンドボックス試行を途中停止したため）。

Wall-clockはMaven wrapper起動から終了までのStopwatch実測。
Maven全体はログのTotal time。テストフェーズは時刻付きログのSurefire開始行から
BUILD結果行までの差（fork起動、レポート出力、終了処理を含む、ログ境界精度の値）。
XML suite合計はこれらと別の指標で、メソッドtime合計とも異なる。
今回は逐次実行だが、非同期処理やfixtureもあり全指標は一致しない。
将来並列化するとsuite時間の合計はwall-clockより長くなり得る。

測定前のCLI引数誤指定（テスト0件）とサンドボックス試行は成功runから除外した。
後者は初回コンパイル後、Mockito/Byte BuddyのJVM接続ヘルパーで進行が止まった。
親子関係とコマンドを確認した当該3Javaプロセスだけを停止して、通常権限で再実行した。
これは成功扱いせず、性能ベースラインにも含めない。通常権限のrunはJVMの起動権限以外、
テストのassertion・試行数・タグ・fork・並列設定を変更していない。

## 4. 件数と分類

| ソース上の明示タグ | ファイル数 |
| --- | ---: |
| integration | 428 |
| e2e | 6 |
| live | 4 |
| 未タグ（Unit/Component混在） | 830 |
| 合計 | 1,268 |

通常形`@Tag`と完全修飾のJupiter/jqwikタグを検索したファイル単位の分類（複数あればlive > e2e > integrationの順）。内部クラス・メソッド単位・継承の実効タグまでは再現しないため、これはXMLの実行クラス数ではない。Unit/Componentの厳密な件数は自動分類していない。XML自体にはタグがないため、タグ別の正確な実行件数・時間は今回未計測。

既存の条件によるskipを保存しており、環境不足を成功と見なしていない。代表runのskip内訳:

- `dev.mikoto2000.rei.chatgpt.WindowsPlanAppServerProcessTest#nativeProcessHasDeadlineEvenWhenNoRpcIsSent`: Environment variable [REI_TEST_WINDOWS_CODEX] does not exist
- `dev.mikoto2000.rei.chatgpt.WindowsPlanAppServerProcessTest#startsNativeRestrictedThreadThenDeletesOwnHome`: Environment variable [REI_TEST_WINDOWS_CODEX] does not exist
- `dev.mikoto2000.rei.chatgpt.WslPlanAppServerProcessTest#startsRestrictedThreadWithoutTurnThenClosesNativeProcessAndDeletesOwnHome`: Environment variable [REI_TEST_WSL_CODEX] does not exist
- `dev.mikoto2000.rei.core.DocumentRendererProcessTest#realPlantUmlErrorAndPngAreVerifiedAndDeliveredThroughExistingStore`: PlantUML jar未設定（ソースのAssumptions条件）
- `dev.mikoto2000.rei.core.StructuredHttpBenchmarkTest#compareShellAndStructuredApiAgainstIdenticalFixture`: System property [httpapi.benchmark] does not exist
- `dev.mikoto2000.rei.storage.AnswerGenerationIoBenchmarkTest#measure`: System property [rei.io.benchmark] does not exist
- `dev.mikoto2000.rei.storage.StorageReadReuseTest#measureRepeatedReads`: System property [rei.io.read-benchmark] does not exist
- `dev.mikoto2000.rei.temporal.holiday.OfficialHolidayCsvTest#validatesDownloadedOfficialCsv`: System property [holiday.csv] does not exist

代表runはXML 1264 suites、そのうち1件以上実行したクラスは **1259**、全ケースskipのsuiteは5。上位占有率・分布・相関は全XML suitesを対象とする。

ソース上ではlive指定4クラス（LiveEnvironmentE2ETest、ReviewQualityLiveTest、
VoiceCorrectionLiveTest、OpenAiWebSearchLiveTest）。jqwikの各Propertyの試行数は
XMLのテスト件数とは別であり、XML件数に展開して加算していない。
ネストクラスや複数エンジンによってsuiteとJavaソースファイルは一対一でない。

## 5. 遅いテストクラス上位20

| 順位 | クラス（dev.mikoto2000.rei.を省略） | 件数 | 秒 | suite合計比 | failure / error / skip |
| ---: | --- | ---: | ---: | ---: | --- |
| 1 | `application.task.TaskSourcePaginationTest` | 2 | 34.350 | 4.01% | 0 / 0 / 0 |
| 2 | `core.SelfPatchReviewGitTest` | 12 | 33.316 | 3.89% | 0 / 0 / 0 |
| 3 | `paper.PaperSafetyTest` | 14 | 30.066 | 3.51% | 0 / 0 / 0 |
| 4 | `core.ToolsTest` | 113 | 29.625 | 3.46% | 0 / 0 / 0 |
| 5 | `externalagent.IsolatedImplementationServiceTest` | 5 | 28.361 | 3.31% | 0 / 0 / 0 |
| 6 | `storage.StorageEventMigrationTest` | 16 | 28.119 | 3.28% | 0 / 0 / 0 |
| 7 | `core.PagedFileSearchTest` | 3 | 25.399 | 2.96% | 0 / 0 / 0 |
| 8 | `externalagent.SpecificationDelegationIntegrationTest` | 7 | 23.966 | 2.80% | 0 / 0 / 0 |
| 9 | `durable.SnapshotScaleTest` | 2 | 17.420 | 2.03% | 0 / 0 / 0 |
| 10 | `goal.GoalStageEvaluationTest` | 1 | 16.348 | 1.91% | 0 / 0 / 0 |
| 11 | `checkpoint.PersistentCheckpointServiceTest` | 62 | 16.140 | 1.88% | 0 / 0 / 0 |
| 12 | `storage.StorageMigrationCoordinatorTest` | 27 | 15.743 | 1.84% | 0 / 0 / 0 |
| 13 | `checkpoint.CheckpointHistoryTest` | 23 | 14.750 | 1.72% | 0 / 0 / 0 |
| 14 | `review.ReviewToolsTest` | 19 | 12.892 | 1.50% | 0 / 0 / 0 |
| 15 | `storage.SessionTurnMigrationTest` | 16 | 12.711 | 1.48% | 0 / 0 / 0 |
| 16 | `goal.GoalWaitIntegrationTest` | 19 | 11.800 | 1.38% | 0 / 0 / 0 |
| 17 | `ui.shell.CommandHelpTest` | 1 | 10.706 | 1.25% | 0 / 0 / 0 |
| 18 | `externalagent.ImplementationRequestServiceTest` | 23 | 9.691 | 1.13% | 0 / 0 / 0 |
| 19 | `attention.AttentionDeliveryTest` | 20 | 9.192 | 1.07% | 0 / 0 / 0 |
| 20 | `voice.VoiceInputCoordinatorTest` | 26 | 9.003 | 1.05% | 0 / 0 / 0 |

割合の分母は同一runの全XML suite時間合計。ネストクラスのXML識別名を保持し、
ソースファイル単位に恣意的に併合していない。

## 6. 遅いテストメソッド上位20

| 順位 | クラス（同prefix省略） | XMLメソッド名 | 秒 | 結果 |
| ---: | --- | --- | ---: | --- |
| 1 | `paper.PaperSafetyTest` | `transportRejectsPrivateTargets(String)[4]` | 30.004 | Passed |
| 2 | `application.task.TaskSourcePaginationTest` | `retainedGoalsSchedulesAndCheckpointsAreNotTruncatedToRecentSourceWindows` | 28.245 | Passed |
| 3 | `core.PagedFileSearchTest` | `moreThan1024FilesResumeWithNoMissingTarget` | 23.622 | Passed |
| 4 | `storage.StorageEventMigrationTest` | `hundredThousandEventsMigrateInSixtyFourMiBWithoutChangingSource` | 21.723 | Passed |
| 5 | `durable.SnapshotScaleTest` | `tenThousandFilesDeepDirectoriesAndPagedTargetsHaveNoMissingOrDuplicatePath` | 17.266 | Passed |
| 6 | `goal.GoalStageEvaluationTest` | `comparesFourModesAcrossTenSafetyCasesAndTwoRecoveryCases` | 16.348 | Passed |
| 7 | `ui.shell.CommandHelpTest` | `everyShellCommandAndAliasSupportsHelpWithoutExecutingItsBody` | 10.706 | Passed |
| 8 | `review.ReviewToolsTest` | `pageReceiptDoesNotRepeatTheGrowingReviewLedger` | 9.455 | Passed |
| 9 | `core.SelfPatchReviewGitTest` | `diagnosedRepairConnectsRealFailureReportApprovalApplyAndRealShellVerification` | 6.696 | Passed |
| 10 | `architecture.PackageArchitectureTest` | `productionDependenciesMustNotGrow` | 6.590 | Passed |
| 11 | `externalagent.IsolatedImplementationServiceTest` | `dirtyParentAndStaleParentRejectWithoutLosingEdits` | 6.474 | Passed |
| 12 | `externalagent.IsolatedImplementationServiceTest` | `aConcurrentConflictingCommitIsDetectedWithoutAbortingOrRetryingTheMerge` | 6.370 | Passed |
| 13 | `externalagent.IsolatedImplementationServiceTest` | `parentStaysUnchangedUntilExplicitVerifiedMergeAndReceiptSurvivesRestart` | 6.198 | Passed |
| 14 | `externalagent.IsolatedImplementationServiceTest` | `modifiedCommittedWorktreeRequiresReReviewBeforeMerge` | 6.109 | Passed |
| 15 | `application.task.TaskSourcePaginationTest` | `paginationIncludesRetainedTerminalSourcesBeyondTheirExistingRecentListWindow` | 6.105 | Passed |
| 16 | `externalagent.ImplementationDelegationIntegrationTest` | `explicitCommandSharesOneDelegationAndParentModelBudgetWithRealIsolatedVerification(Agent)[2]` | 6.102 | Passed |
| 17 | `externalagent.SpecificationDelegationIntegrationTest` | `approvedRequirementsUseExistingEngineAndManifestRejectsOutsideScope(int)[1]` | 5.880 | Passed |
| 18 | `externalagent.SpecificationDelegationIntegrationTest` | `approvedRequirementsUseExistingEngineAndManifestRejectsOutsideScope(int)[6]` | 5.759 | Passed |
| 19 | `durable.WorkItemRecoveryTest` | `stableTaskRecoversWithoutCheckpointAfter101RunsAndNeverReexecutesCompletedWork` | 5.641 | Passed |
| 20 | `core.SelfPatchReviewGitTest` | `savedChangeSetRepairsRealGitFindingThenRunsFinalRealShellTest` | 5.568 | Passed |

XMLのclassname / nameをそのまま保存し、パラメータ番号やDynamicTest名を保持する。
同名のtestcaseも別行として残す。メソッドtimeに含まれないclass fixture/context起動は
クラス時間で確認する。成功件数はsuiteの tests − failures − errors − skipped。

## 7. 実行時間の分布

| 上位クラス数 | suite時間占有率 |
| ---: | ---: |
| 10 | 31.16% |
| 20 | 45.48% |
| 50 | 62.93% |
| 100 | 76.21% |

| クラス時間 | クラス数 |
| --- | ---: |
| <1s | 1113 |
| 1-5s | 117 |
| 5-10s | 17 |
| 10-30s | 14 |
| >=30s | 3 |

テスト件数とクラス時間のPearson相関: **0.2710**。

Pearson相関はクラスごとのXML testsとsuite秒数の線形相関で、因果を示さない。
試行回数、DB件数、起動回数、待機契約などはtests件数に現れない。

## 8. Spring Context・DB・外部依存

### Context

`@SpringBootTest` は3クラス（ReiApplicationTests、ComputerUseApplicationTest、
SubAgentIntegrationTest）。`@WebMvcTest` 等のslice、`@DirtiesContext`、旧`@MockBean`は検出なし。
3クラスに計16の`@MockitoBean`指定がある。computer-useの条件プロパティと追加mock、subagentの
permissionプロパティと`@DynamicPropertySource`が異なり、同じContext構成ではない。
異なるfeatureの配線検証なので単に共通化すれば検証範囲を変える。

初回の3クラスのsuite時間はComputerUseApplicationTest 10.216秒、SubAgentIntegrationTest
2.203秒、ReiApplicationTests 2.097秒、合計14.516秒（XML全体の約1.49%）。
これはContext起動だけの時間ではなく各クラス全体の時間なので、起動時間そのものとは呼ばない。
この3クラスだけのcache改善に全体短縮の主役を期待する根拠は弱い。

全件測定後、別JVMの診断runでこの3クラスだけを選択し、6件すべて成功（failure/error/skip 0）。
wall 17.272秒、Maven 15.159秒、XML suite合計12.236秒。この値は全件3回の統計には含めない。
`org.springframework.test.context.cache=DEBUG` の最後の統計は次のとおり。

```text
size = 3, maxSize = 32, parentContextCount = 0,
hitCount = 69, missCount = 3, failureCount = 0
```

この診断範囲では3構成のContext load（cache miss）を観測し、容量不足による再生成の兆候はない。
hitはテスト件数ではなくキャッシュアクセス回数。**全件runのhit/missや全Contextの起動回数ではない**。
再現時は次を `target/test-performance/context-logback.xml` に保存してから実行する。

```xml
<configuration>
  <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder><pattern>%level %logger - %msg%n</pattern></encoder>
  </appender>
  <logger name="org.springframework.test.context.cache" level="DEBUG"/>
  <root level="WARN"><appender-ref ref="CONSOLE"/></root>
</configuration>
```

```powershell
$configUri = ([uri](Resolve-Path target/test-performance/context-logback.xml).Path).AbsoluteUri
./scripts/measure-tests.ps1 -Label phase0-context -Repository '<dependency-cache>' `
  -MavenArguments @('test','-Pfull','-B',
    '-Dtest=ReiApplicationTests,ComputerUseApplicationTest,SubAgentIntegrationTest',
    "-Dlogging.config=$configUri",
    '-Dorg.slf4j.simpleLogger.showDateTime=true',
    '-Dorg.slf4j.simpleLogger.dateTimeFormat=HH:mm:ss.SSS')
```

一方で `SpringApplicationBuilder` / `new SpringApplication` を使うソースは21ファイル。
たとえばGoalHttpTestはメソッドごとにloopback HTTPサーバーを起動・closeする。
これらの手動ContextはTestContextキャッシュの対象外であり、cache hitを増やすだけでは減らない。
ソース中の起動箇所数は実際の起動回数ではない。全プログラマティックContextの実起動回数や
refresh単独時間は未計測（今回のXMLにそのイベントがなく、全面的instrumentationは加えていない）。

明示的なキャッシュ容量変更なし。Springの既定上限32とDEBUG統計の意味は
[Spring公式Context Caching](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/caching.html)を参照。
容量不足と判断する前に、cache missと手動起動を分ける必要がある。

### DB

`jdbc:sqlite:`を含むテストソースは257ファイル。`@TempDir`内のファイルDBを作り、
repository constructorでCREATE TABLE / INDEX IF NOT EXISTS等を実行する構成が多い。
今回のテスト経路に独立したFlyway/Liquibase実行フェーズはない。
`TaskSourcePaginationTest`は270 goal、270 schedule、1,005 checkpointと、別ケースの270 dependencyを
実ファイルDBに作る。件数上限を越えてもpaginationが欠落しない契約に必要なデータであり、
件数削減はそのまま採用できない。fixtureの作成と読み取り/検証を分離して次Phaseで計測すべき。

`SqliteTestDatabase`は1試行1つの`:memory:`物理接続とclose抑制された論理接続を使い、
試行末に物理closeする。MemoryServicePropertyTest / MemoryExporterPropertyTestにはすでに適用済み。
ファイル永続化・再起動・FTS・sqlite-vecの契約まで一律にメモリDBへ変える案は不適切。
sqlite-vecのバイナリはversion/platformごとの`target/sqlite-vec-test-cache`で再利用し、DBは分離する。
初回は外部ダウンロードがあり得るため最初の通常権限runと後続runの差を確認する。
DB初期化・SQL・fsync・cleanupそれぞれの独立時間はXMLでは分離できず未計測。

独自の`StorageMigrationCoordinator.prepare()`も多くのfixtureで実行する。
StorageEventMigrationTestの10万イベント、SessionTurnMigrationTestの10万sessionは
`-Xmx64m`の子JVMで移行可能なことを検証するscale契約であり、fixture件数やheap制約を
下げて速度を稼ぐ対象ではない。SnapshotScaleTestも10,001ファイルと深さ30のディレクトリを
作成する。こうしたscaleテストは日常サイクルとの実行タイミング分離を先に検討する。

### 外部境界・待機

自動fullのHTTPはloopbackのfakeサーバーやポート0、LLMはmock/fakeが中心。
実API/ローカルLLMの品質評価はlive側。sqlite-vec初回取得は外部通信の例外。
全通信のパケット追跡はしていないため「外部通信が絶対に0」とは断定しない。
初回のPaperSafetyTestでは、非公開URL拒否ケース
`transportRejectsPrivateTargets(String)[4]`（`http://2130706433/a`）が30.003秒だった。
run 2は30.004秒、run 3は30.008秒で再現した。run 2の同じparameterized testの他ケースは0.001〜0.004秒だった。
PaperPropertiesの全体timeoutも30秒であり、他の拒否ケースと切り分ける候補。
数字1要素のhostはPublicNetworkPolicyの通常のIPv4 literal判定に入らず、resolver経路を通り得る。
DNS/接続/拒否のどの段階で時間を使ったかは未計測で、timeout由来との断定はしない。
このテストの意図はprivate targetの拒否であり、30秒の待機そのものではない。
次Phaseでは拒否原因のassertionとresolver観測を追加して、安全性の境界を維持しながら
環境依存の待ちを取り除けるか確認する。
実Git、PowerShell、JVMの子プロセス起動はシステム契約の検証に必要。
ToolsTestにはtimeout、background昇格、kill、stdout/stderrの非同期観測と25/50msのpollingがある。
長寿命fixtureの10秒sleepはkillで終わるため、その字面をそのまま待機時間に足してはいけない。
MemoryConsolidateCommandTestの1.2秒待ちは1秒timeout契約であり、単純短縮は検証を損なう。
`Thread.sleep`を含むソースは44ファイル。固定待機とdeadline内のpollingを区別し、
次Phaseでは完了イベントで置換できる箇所から調べる。

DBやファイルは多くがTempDirで分離されるが、共有データディレクトリ、System.setOut、
System.setProperty、LoggingSystem、static状態、キャッシュ、環境の変更がある。
並列化にはResourceLockやプロセス分離の設計と競合検証が必要。

## 9. 現在の基盤の問題点

1. 初回はwall 981.168秒に対しXML suite合計971.798秒。時間の大半はテスト側で、Maven wrapperや非テストフェーズだけの調整では大幅な短縮を期待できない。
2. 最上位クラスは初回SelfPatchReviewGitTest（43.391秒）。TaskSourcePaginationTestの2件36.782秒のように、件数だけでは重さを予測できない。
3. DB・大量ファイル・子プロセス・timeoutに負荷が分散する。初回上位20クラスでも45.39%であり、1クラスだけ直せば全体が解決する状況ではない。
4. fast/fullは既存だが、分類が実装の成長に追いついていない。SelfPatchReviewGitTest、IsolatedImplementationServiceTest、SnapshotScaleTestにはクラス単位のintegration/e2eタグがなく、PagedFileSearchTestは子JVMを起動する1メソッドのみintegration指定である。1,030ファイルを扱う別メソッドはfast側に残る。実行範囲を確認せず「既存fastは純粋Unitのみ」と見なせない。
5. Springのキャッシュ対象3クラスと、手動で生成するContextを分けて評価する必要がある。fork増加でキャッシュが分断されれば逆効果もある。
6. テスト計測はJava fullを対象とし、実環境用live・条件付きskipやclient/nativeの保証を代替しない。fast単独のwall時間とその短縮率は未計測。

`check-test-partition.ps1`は既存の再利用候補だが、failure/errorだけでなくskipが1件でもあると
拒否する実装である。現在の既存skipを含むfullにそのまま適用して集合一致を証明することはできない。
次Phaseではskipの識別名・理由が想定どおりであることを別に検証する方針を決め、
実行対象の欠落検査を維持したまま適用する必要がある。今回このgateは緩和していない。

旧集計ツールはmethod成否、Failsafe識別、上位20/100占有率、分布、相関が不足していた。
既存ツールを拡張し、別ツールとの二重管理を避けた。XML不正/空入力/重複suiteを失敗させ、
同一provider+suiteの重複はコピーか再実行か判別できないので拒否する。
再測定は別label/別出力で管理する。Surefireの再試行設定は現在なし。
将来retryを使う場合、flakyFailure/rerunFailureの試行履歴を別途扱う必要がある。

## 10. 改善候補の比較

下記は**未実装・効果未測定**。時間短縮率を実測したかのように提示しない。
優先順位は現状の証拠、効果の確かさ、品質維持の容易さから判断する。

各数値は**初回run 1の実測**。効果欄は今後検証する仮説で、短縮実績ではない。

| 優先 | 施策 | 期待効果と根拠 | 難易度 | 回帰見逃しリスク | 安定性への影響 |
| ---: | --- | --- | --- | --- | --- |
| 1 | DBセットアップ・fixture作成の効率化 | TaskSourcePagination 36.782秒、PagedFileSearch 32.294秒、StorageEventMigration 30.049秒等。まずsetup/SQL/検証/cleanupを分解し、重複schema準備・接続・fixture投入の削減余地を測る。短縮率未測定 | 中〜高 | 中。実ファイル・migration・restart契約をメモリDBやmockに置換すると高まる | 試行別DB分離を保てば改善可能。共有fixture化は順序依存の危険 |
| 2 | 不要な待機・外部依存・プロセス準備の削減 | PaperSafetyの1ケース30.003秒、SelfPatchReviewGit 43.391秒、IsolatedImplementation 37.036秒、Tools 33.830秒。拒否原因、Git fixture、完了観測を切り分ける。合計時間全てが削減可能という意味ではない | 中 | 中。timeout/cancel/killや実Git境界を失わないこと | DNS等の環境待ちを除ければ改善。単なるtimeout短縮はflake増加 |
| 3 | Unit/Integrationの実行タイミング分離を再点検 | 既存test/full/integration-onlyを再利用。上記の未タグscale/processテストを棚卸し、既存skipの検証方針を整えてからcheck-test-partitionによる集合一致を確認する。日常feedbackの改善余地はあるがfast所要時間は未測定 | 低〜中 | fullをPR/merge必須で維持すれば低い。fast成功だけを完了扱いすると高い | 実行順・条件を変える分類の検証が必要。全テスト/試行は維持 |
| 4 | CIテスト分散 | XML時間を重みとして全クラスを複数jobに割り当てる。既存単一job、上位20で約45%の偏り。起動/compile/cache転送を含む効果は未測定 | 中 | shard集合の欠落・重複検出が必須。全件の合併が一致すれば低い | 別VMの隔離は利点。依存初回downloadと環境差は増える |
| 5 | Spring Contextキャッシュ効率 | @SpringBootTestは3構成、手動起動ソースは21ファイル。cache容量拡張より、同じ構成を手動で何度も起動する箇所の共有可否を確認。正当なfeature差の統合はしない | 中 | 中。条件Bean・scope・HTTP認証状態の漏れに注意 | 汚染しないContextのみ再利用。close漏れや状態共有は不安定化 |
| 6 | JUnit並列実行 | 現状逐次。純粋Unitから対象を選んで評価する余地があるが、static/property/stdout/DB/LoggingSystem変更があり一括有効化はできない。速度効果未測定 | 高 | 順序競合で検証が形骸化するリスク、中〜高 | ResourceLock、データ分離、負荷反復の検証が必要 |
| 7 | Surefire/Failsafe fork最適化 | 現在1fork/reuse=true。複数forkでクラスを分散できる一方、JVM・Mockito・Context起動とメモリを重複。単一モジュールなので-Tだけでは解決しない。効果未測定 | 中〜高 | 中。共有外部状態はforkをまたいでも競合する | JUnit同一JVM並列よりstatic隔離は強いが、I/O競合と負荷増の可能性 |
| 8 | 変更影響分析による自動選別 | 開発時の候補先行実行は有望。既存READ候補は上限/partial/動的依存の未解決があり、現状は全件省略の根拠にならない。短縮率・recallとも未測定 | 高 | 全件省略時は高い。advisory/全件併走なら低い | fallbackとfreshnessの契約が必要。導入当初は実行総量が増え得る |

[Surefire公式fork説明](https://maven.apache.org/surefire/maven-surefire-plugin/examples/fork-options-and-parallel-execution.html)
のとおり、forkを増やすとJVMとContextが増える。CPU数に比例した高速化は仮定しない。

## 11. Test Impact Analysisの適用可能性

既存実装は `RepositoryMapService`、`ChangeTestImpactService`、`GitChangedFiles`、
`CoverageImpactService` と [Change/Test Impact](change-test-impact.md)、
[Coverage/Test Impact](coverage-test-impact.md)で確認した。

| 能力 | 現在 | 次に必要なもの |
| --- | --- | --- |
| Git変更path | staged / unstaged / untrackedをNUL区切りで収集、rename旧新path、削除を保持 | CIのbase/head差分と原子的snapshot、変更中の無効化 |
| Java解析 | javac ASTで宣言・明示import、逆importを推移的BFS、Test名候補 | wildcard、同package参照、継承/implementsの独立edge、動的解決 |
| 多言語 | TS/JS/Rust/Go/Pythonのheuristic候補、partialで限界表示 | 言語ごとの正確さ評価 |
| 限界・広い回帰 | missing/unindexed、partial、build設定、cross-module等でBROAD_REGRESSION_REQUIRED | 実行器におけるfail-closedな全件フォールバック |
| coverage | JaCoCo/Cobertura/LCOVの行観測、stale/unmeasured、LCOV TNの明示名 | commit一致とtest単位のcoverage。集約JaCoCoからcovering testは逆算できない |
| テスト実行 | 候補提示のみ、コマンド生成/省略判断/実行は行わない | Maven runnerと全件回帰との照合 |

継承はextends先を意味解析しているわけではない。明示importがある継承ならそのimport edge経由の
候補になるが、同package継承などは保証しない。Spring DI、条件Bean、reflection、resource、SQL、
schema、設定変更は構造edgeだけで閉じないので**全件**を原則にする。
既存の広い回帰指示は助言であり、自動全件実行が実装済みという意味ではない。
現在の索引には1,024ファイル/16MiB/10秒等の上限があり、productionだけで1,702 Javaファイルの
本リポジトリ全体を無条件に網羅できると考えない。候補数limitは最大100、未指定は20。
partial=falseも完全性の保証ではない。

将来は安全に許可したJUnitクラスFQCNを `-Dtest=...` に渡し、プロファイルのタグ除外も考慮する。
Failsafeを導入した場合は `-Dit.test=...` と `verify` の対応も別に必要。
空候補、構文失敗、上限到達、stale coverage、ソース外変更、実行器障害、索引のcommit不一致は
全件に戻す。初期運用は「候補を先に実行し、その後full」から始める。
全件結果との差で、候補に含まれなかった失敗を記録し、実際の回帰・変異導入で取りこぼしを評価する。
全件が成功した観測だけでは選別のrecallを証明できない。PR/mergeのfullは維持する。

## 12. 次Phaseと検証・成果物

**Phase 1-A**: 重いクラス・メソッドのsetup、DB schema/接続、fixture投入、検証、cleanupを診断専用のtimerで分ける。PaperSafetyの数字host拒否ではresolverと終了理由を観測する。Source上の重い未タグケースを洗い出し、fastとintegration-onlyを測定してfullとの集合一致を確認する。10万件/1万ファイル/容量上限越え、timeout/cancel等の契約・試行数は維持する。

**Phase 1-B**: 実測で重い準備処理から1件ずつTDDで改善する。たとえば独立したDB状態を保つfixture生成の効率化、同一Git初期snapshotからの安全な複製、固定待機の完了イベント化を比較実験する。変更前後を同一環境で3回測り、failure/error/skipと全件テスト集合が変わらないことを必須にする。今回の測定だけでは削減秒数を約束しない。

**Phase 2**: TIAをshadow運用し、全件と併走して候補・全件失敗の差分を保存する。索引上限、commit一致、動的依存、fallback、test単位coverageを先に解決する。CI fullを維持したまま、選別の取りこぼしを回帰事例・変異で評価してから省略の可否を判断する。並列/fork/CI分散は独立した実験とし、TIAの効果に混ぜない。

今回の集計テストはRed（旧実装では複数入力を解析できず失敗）→Greenで追加。
Surefire / Failsafe、小数秒、parameter/dynamicの識別名、failure/error/skip、空ディレクトリ、
不正/空XML、DOCTYPE拒否、重複、0秒、相関未定義、並列wallとの差を検証する。
CIのJava workflowにも同じ契約テストを追加した。測定専用ログ設定はtarget配下に分離し、
通常のアプリ/テスト用logbackやPOMは変更していない。

成果物は本書、拡張済み `scripts/test-performance-report.ps1`、
`scripts/test-test-performance-report.ps1`、CIの集計テストstep、
3回の数値を保存する[集計JSON](test-performance/phase0-summary.json)。
生XML/ログ、ローカルパス入りtiming.json、ネイティブバイナリ、一時設定はGit管理しない。
