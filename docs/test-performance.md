# Java テスト性能レポート

測定日: 2026-10-04（Asia/Tokyo）。ブランチ: `codex/test-performance-improvement`。

## 1. 現状と測定条件

このレポートの計測時点の基盤は **JDK 25 / Spring Boot 4.0.4 / Maven Wrapper 3.9.14 / Surefire 3.5.5**。現在の依存バージョンは `pom.xml` と [更新ガイド](spring-ai-2-upgrade.md) を参照してください。
依頼の Java 17 / Boot 3.x へは変更していない。Windows 11、同じチェックアウト、同じ
`C:\Java\jdk-25`、同じローカル依存キャッシュ `.m2/repository`、オフライン Maven で測定した。
`-o` は Maven の依存解決だけを制限し、テスト内部の HTTP は制限しない。
Maven 起動から終了までの wall time を Stopwatch で計測し、XML の suite 時間も別に集計した。
測定中に別のビルドや Client テストは実行していない。

初回のサンドボックス実行は既定ログ保存先と GitHub ダウンロードへのアクセスで失敗した。
これは有効な性能ベースラインに含めない。以後は `REI_DATA_DIR` を
`target/test-performance/data` に固定し、localhost と既存ネイティブ拡張のダウンロードが
利用できる条件で測定した。改善後は Surefire が同じ保存先を設定する。
必要なら `-Dtest.dataDirectory=<専用の絶対パス>` で変更できる。

改善前の成功した2回は **2,626件、506クラス、failure/error/skipped 全て0**。
wall time は **139.714秒 / 143.988秒**、平均 **141.851秒**。
どちらもコンパイル済みで、改善前コードは両測定の間に変更していない。
プロパティテストの試行数は XML のテスト件数とは別で、試行数も削減していない。

変更前は POM に Surefire/Failsafe の個別設定がなく、Surefire の既定値
`forkCount=1`、`reuseForks=true`、JUnit 並列実行無効で実行していた。
Failsafe は親 POM の pluginManagement に存在するが、本プロジェクトのライフサイクルには
登録されていない。既存開発コマンドは `mvn test`。今回も既存の全 `*Test` / `*Tests`
を Surefire で実行し、Failsafe に移動していない。
改善後は fork と逐次実行を明示し、テストが0件ならビルドを失敗させる。

## 2. テスト分類

| 分類 | クラス数 | 選択方法・役割 |
| --- | ---: | --- |
| Unit | 138 | 未タグ。Parser、Validator、値・変換・計算など |
| Component | 156 | 未タグ。複数クラスと既存 Fake / InMemory / Mockito、軽量なメモリ内契約検証 |
| Integration | 207 | `integration`。実 SQLite / filesystem / Spring Context / HTTP サーバー、非同期タイムアウト境界 |
| E2E / System | 6 | `e2e`。実シェル / Git / JVM プロセスを起動するシステム境界検証 |

クラス単位で保守的に分類した。軽いメソッドと重いメソッドが混在するクラスは全体を
Integration / System とし、大規模なファイル移動やテストの分割はしていない。
`*IntegrationTest` という名前でも Fake だけなら fast に残る。分類の基準は名前ではなく依存。
JUnit Jupiter のクラスには `org.junit.jupiter.api.Tag`、jqwik のみのクラスには
`net.jqwik.api.Tag` を使う。エンジンごとのタグ解釈の違いによる実行漏れを避ける。
Unit / Component の内訳はソース上の依存に基づく棚卸しで、Maven の選択境界は
「未タグ / integration / e2e」の3区分である。

全クラスとリソース指標は [改善前](test-performance/inventory-before.csv) /
[改善後](test-performance/inventory-after.csv) の CSV に記録した。
指標はソース上の検出候補であり、実 I/O の実行回数ではない。

| リソース | 判断と実行先 |
| --- | --- |
| SQLite / repository | SQL・FTS・永続化に必要。Integrationに残し、2つのプロパティクラスは実メモリDBへ |
| filesystem / temporary directory | FileStore、Session、artifact、export、restartには実I/Oが必要。Integration、原則 `@TempDir` |
| HTTP / socket / port | 認証・SSE・client境界はloopbackサーバー、port=0。Integration。ロジックは既存mock/fake |
| process | shell / Git / JVMの起動・cancel契約に必要。System。一般ロジックはmockを維持 |
| Thread / Executor | queue、cancel、orderingの実非同期契約を保持。Future/latchで同期 |
| sleep / polling / timeout | 完了観測の1箇所をイベントへ。timeout契約・長寿命process fixtureは残す |
| EventBus | 既存InMemoryでscope、subscriber、replayを検証。global共有にはしない |
| global / static / singleton | stdout、property等を扱うテストは逐次実行。業務状態を共通fixtureへ移さない |
| System properties / environment | ReiDataDirectory、platform、startup設定の契約に必要。明示引数によるresolve検証も維持 |
| Spring Context | conditional Bean、scope、root wiringに必要。不要なdomain起動は見つからなかった |
| Testcontainers | 使用なし。追加しない |
| 実LLM / 外部API | 自動テストはfake/mock、検索はローカルサーバー。手動 `ComputerVisionReplay` は自動suiteではない |
| 外部ダウンロード | sqlite-vecの初回準備だけ。installerのdownload/checksumテストは注入fakeで維持 |

## 3. ボトルネック

改善前2回目の上位10クラスは suite 時間合計の **59.44%**（wall time の **56.92%**）、
上位50は **81.10%**（wall time の **77.65%**）を占めた。
fixture / Context 起動を含む class suite 時間を用いる。メソッド平均・最大値は testcase 時間で、
class suite 時間とは集計範囲が異なる。

- `ToolsTest`: 26.167秒。Git と PowerShell / shell / プロセスの実起動、終了・キャンセルの待ち。
- `MemoryServicePropertyTest`: 24.063秒。各試行の新規ファイルDB、スキーマ・FTS初期化、繰り返す接続。
- `VectorDocumentServiceTest`: 6.850秒。メソッドごとに sqlite-vec をダウンロードし直す。
- `ComputerUseApplicationTest`: 6.826秒。実アプリケーションの配線検証。
- `MemoryExporterPropertyTest`: 4.232秒。出力ファイル検証と不要なディスクDBの組み合わせ。

TOP 50、メソッド TOP 20、改善後 TOP 20 は
[詳細ランキング](test-performance/slow-tests.md) を参照。

## 4. 改善内容

1. **fast と full をタグで分離**。既存の全テストを full に残し、削除・skip・試行数削減をしていない。
2. **実SQLiteのメモリDBを各プロパティ試行に割り当て**。
   `SqliteTestDatabase` は実 SQLite と FTS5 を維持する。
   1試行で1つの物理接続を所有し、JdbcClient の論理 close は抑制、試行終了時は物理接続も close。
   別試行との共有はない。接続寿命、論理接続を越える保持、DB分離を2つのテストで検証した。
   最初に未実装 fixture のテストを追加してコンパイル失敗を確認し、最小実装で green にした。
   `MemoryServicePropertyTest` と `MemoryExporterPropertyTest` の全試行と既存 assertion を保持。
   export の JSONL 行数 assertion も追加した。
3. **ネイティブバイナリだけを再利用**。
   `SqliteVecTestExtension` は `target/sqlite-vec-test-cache/<version>/<platform>` を使用する。
   fresh installer 間のキャッシュ再利用は既存 installer テストの assertion を追加して確認した。
   初回は既存 installer の manifest / checksum 検証付きダウンロードが必要。
   DB、ドキュメント、書き込み先は各テストの `@TempDir` のまま。
4. **時間と非同期観測を決定的にする**。
   メモリの期限プロパティに既存 constructor の fixed Clock を使用。
   `BackgroundRunApiTest` の10ms pollingを、対象プロジェクトの完了イベントの Future 待ちへ変更。
   待機上限は従来どおり1秒、状態 assertion を維持し、購読を finally で解除する。
5. 計測・ランキング・リソース棚卸し・fast/fullの実行漏れ照合スクリプトを追加。

production code と dependency は変更していない。
既存の InMemory EventBus、InMemory repositories、Fake LLM / embedding を維持した。
今回の高コストな SQL / FTS プロパティは実DBの正しさを検証しているため、Repository Fake に
置き換えるより、実SQLiteを保つメモリDBの方が適切だった。
ファイル永続化、restart、SqliteVectorStore、接続境界の integration tests は残っている。

## 5. Maven コマンド

```bash
# 日常の Red → Green → Refactor
./mvnw test

# Integration / System だけ
./mvnw test -Pintegration-only

# 全テスト（性能比較もこの条件）
./mvnw test -Pfull

# 全テスト + packaging / verification
./mvnw verify -Pintegration

# 重いクラス内の対象テストを絞る場合もタグ除外を解除する
./mvnw test -Pfull -Dtest=ToolsTest
```

Windows は `./mvnw` を `.\mvnw.cmd` に置き換える。
`mvn` がインストール済みなら同じ引数で使用可能。
`verify -Pintegration` は fast も含む全テストである。
`verify` だけでは fast のみになるため、変更完了時・CIの全検証には必ずプロファイルを指定する。
`-Pfull` と `-Pintegration` は同じテスト選択で、`integration-only` とは同時に使わない。

## 6. fast / integration / full の使い分け

fast は日常の短いTDDループ、integration-onlyはインフラ境界の変更確認、fullは変更完了前の検証。
fastだけの成功で変更全体の正しさを判断しない。
新しいDB / filesystem / Context / ネットワーク / プロセステストには対応タグを付ける。
一時ファイルを使うテストも保守的に integration に分類する。

`scripts/test-resource-inventory.ps1` を再実行し、`Integration candidate` があればレビューする。
ソース指標は間接呼び出しを完全に追跡しないので、テスト対象の実装も確認する。
`scripts/check-test-partition.ps1` は同一コードで実行した fast / integration-only / full の
テストID集合が完全一致し、重複・skip・既存テストの欠落がないことを検証する。

## 7. Spring Context と並列実行ポリシー

Spring Context 利用候補は **33クラス → 33クラス**。
`@SpringBootTest` は **3 → 3**、推定cache構成も **3 → 3**。
fastではこれらを実行せず、Spring Context利用候補は **0クラス**。

| Bootテスト | properties / customization | 残す理由 |
| --- | --- | --- |
| `ReiApplicationTests` | test API key、4つのMockitoBean | root context、認証Bean不在、PicocliのBean登録 |
| `ComputerUseApplicationTest` | test API key、computer-use有効、8つのMockitoBean | 実chat requestのtools登録、workflow配線 |
| `SubAgentIntegrationTest` | test API key、4つのMockitoBean、DynamicPropertySource | 一時定義のregistry、実callbackと履歴分離 |

`SubAgentIntegrationTest` の動的プロパティはテスト専用directoryを必要とする。
ComputerUseは有効化プロパティとdesktop用mockが異なる。
field名も異なるMockitoBeanとcustomizerはcache keyを分けうる。
3つの異なる配線シナリオを共通化しても安全に削減できるContextは見つからなかった。
domainロジックの不要な `@SpringBootTest` は存在せず、pure Java化を強制しなかった。
小さなApplicationContextRunner / 手動Contextはrefresh・scope・条件付きBeanの検証で必要。

`@WebMvcTest`、`@DataJpaTest`、`@DirtiesContext`、`@MockBean`、`@SpyBean`、
`@MockitoSpyBean`、`@ActiveProfiles`、`@TestPropertySource`、`@ContextConfiguration` は0件。
`@DynamicPropertySource` は上記1クラス。Testcontainersと実LLM呼び出しは確認されていない。
Spring / mock の具体的な利用箇所は [Spring棚卸し](test-performance/spring-usage.txt) に記録した。

**今回は並列化を導入しない**。System.out、System properties、static state、EventBus、SQLite、
executor、実プロセスを扱うテストがあり、全体の主因はディスク・プロセス・ダウンロードだった。
pure Unit の多くは既にミリ秒単位で、並列化の利益より実行順・共有状態のレビュー負担が大きい。
Surefireは1 fork / reuse、JUnitはparallel=false。将来は計測に基づいて個別に許可し、
System properties等には ResourceLock、固定port・DB・static stateには隔離を先に行う。
初回の共有ネイティブcache準備を同一checkoutの複数Mavenから同時に実行しない。

## 8. 改善前後の測定結果

最終比較は同じ権限条件・コンパイル済みでの2回の平均。fullは同じ `test` ライフサイクルで、
`verify` のjar packaging時間と混同しない。

| 対象 | Before | After | 改善率 |
| --- | ---: | ---: | ---: |
| 日常 `mvn test` wall time | 141.851秒（全件） | **23.183秒**（fast） | **83.66%** |
| full `mvn test -Pfull` wall time | 141.851秒 | **118.013秒** | **16.80%** |
| Integration/Systemのみ wall time | 未分離・単独測定なし | 105.526秒 | 比較不可 |
| full内Integration/Systemのclass時間合計・2回平均 | 121.835秒 | 96.619秒 | 20.70% |

日常用の改善率には **テスト選択の変更** が含まれる。同じ範囲の速度改善はfullの16.80%。
Integration/Systemのclass合計はwall timeではなく、独立実行とは初期化コストの帰属も異なる。

| 実行 | wall seconds | 件数 | 結果 |
| --- | ---: | ---: | --- |
| before-valid-1 | 139.714 | 2,626 | 成功 |
| before-valid-2 | 143.988 | 2,626 | 成功 |
| after-fast-final-1 | 23.830 | 1,265 | 成功 |
| after-fast-final-2 | 22.536 | 1,265 | 成功 |
| after-integration-1 | 105.526 | 1,363 | 成功 |
| after-full-1 | 125.898 | 2,628 | 成功 |
| after-full-2 | 110.129 | 2,628 | 成功 |

warm fastは30秒以内。再コンパイルを含んだ最初のfastは53.423秒、最初のfullは128.036秒だった。
変更の種類によってコンパイル時間は追加されるため、毎回30秒以内を保証しない。
サンドボックス条件でのfastは37.261 / 39.352秒。
権限条件による差があるので、改善前と同じ条件の最終2回を比較に用いた。
すべての成功実行でfailure/error/skippedは0。

TOP20比較では `MemoryServicePropertyTest` **24.063 → 0.190秒**、
`MemoryExporterPropertyTest` **4.232 → 0.226秒**、`SqliteVectorStoreTest` **2.762 → 0.381秒**。
`ToolsTest` は **26.167 → 25.566秒**で、実プロセス境界の待ちが残る。
単一クラスの比較は各2回目を使用し、全体平均と区別する。

| リソース指標（クラス数） | Before | After | fast内 |
| --- | ---: | ---: | ---: |
| Spring Context候補 | 33 | 33 | 0 |
| SpringBootTest | 3 | 3 | 0 |
| 推定Boot cache構成 | 3 | 3 | 0 |
| SQLite | 67 | 68 | 0 |
| filesystem | 184 | 183 | 0 |

SQLiteクラス数の+1は分離・接続寿命を検証する新テスト。
disk DB生成の削減はクラス数ではなくプロパティ試行ごとのI/O削減として現れる。
fullのID集合について、**fast 1,265 + Integration/System 1,363 = full 2,628**、
相互の重複なし、改善前2,626件の全ID包含をスクリプトで確認した。
同名クラスが別packageに存在するため、IDにはXML testsuiteの完全修飾名を使用する。

`verify -Pintegration` でも2,628件すべて成功した。
通常成果物名では既存jarのrenameが失敗したため、finalNameだけを
`rei-test-performance-verification` に変更した一時POMで再実行し、
**112.317秒、全テストとSpring Boot repackage成功**を確認した。
一時POMは削除し、本体POMの成果物名は変更していない。
最初のoffline verifyはjar pluginのrepository metadata不足で失敗したので、
一時的な空のMaven settingsを使ってCentralで解決した。ユーザーのsettingsは変更していない。
これらのverify実行は、fullの性能比較の平均に含めない。

最終のClient Vitestは **51件成功**、Playwright desktop/mobileは **14件成功**、
Native ClientのRustテストは **78件成功**。

## 9. 残課題と再計測

- `ToolsTest` の実PowerShell / Gitの起動とtimeout契約の待ちは残る。短いtimeoutへの変更や
  mock化でシステム境界の検証を失うことは避けた。
- `MemoryConsolidateCommandTest` の1.2秒sleepは1秒timeoutの実検証。単に短くしない。
  EventBusやProcess stdoutのpollingも、対象の非同期境界を保つため全ては置換していない。
- Client / nativeはJavaの性能測定から独立した検証であり、Javaの改善率に混ぜない。
- Mockitoの初期化も測定した。既存jarを起動時javaagentとして指定する実験は
  fast **37.402秒**で、動的接続の **37.261 / 39.352秒**と同程度だったため採用していない。
  起動時agentは [Mockito公式のJava 21以降の設定](https://javadoc.io/static/org.mockito/mockito-core/5.19.0/org.mockito/org/mockito/Mockito.html#0.3)
  に基づく試行。JDK警告の解消には役立つが、今回の性能改善としては根拠が弱い。
- 複数回の成功はflake不存在の証明ではない。固定Clock・試行ごとのDB分離・購読解除・逐次実行を
  維持し、今回は繰り返し検証で失敗・skipが増えていないことを確認する。
- resource inventoryの全指標はソース上の候補。filesystem候補数の減少と、プロパティ試行ごとの
  数百回のDBファイル生成除去は同じ数え方ではない。

PowerShell 7で再計測（ラベルは毎回新しいものを使用）:

```powershell
./scripts/measure-tests.ps1 -Label fast-1 -JavaHome C:\Java\jdk-25
./scripts/measure-tests.ps1 -Label integration-1 -MavenArguments @('test','-Pintegration-only')
./scripts/measure-tests.ps1 -Label full-1 -MavenArguments @('test','-Pfull')
./scripts/check-test-partition.ps1 -Fast target/test-performance/fast-1/xml `
  -Integration target/test-performance/integration-1/xml -Full target/test-performance/full-1/xml
./scripts/test-resource-inventory.ps1
```

既存のローカル依存キャッシュを使う場合は `-Repository <絶対パス> -Offline` を追加。
各測定はログ・wall time・XML snapshot・classes.csv・methods.csv・TOP50・集計JSONを
`target/test-performance/<label>` に保存する。
ランキングだけなら `scripts/test-performance-report.ps1 -Reports <XMLディレクトリ>`。
計測スクリプトは前回XMLの混入を防ぐため、プロジェクト内Surefireの生成XMLだけを消去してから実行する。
