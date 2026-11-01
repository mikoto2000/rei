# 自動テスト高速化 Phase 1-B

## 対象と測定条件

2026-11-01、Asia/Tokyo。最新mainをfetchし、
`07d2e625340cb9f9adb48b22f86c6f6a33d02b02` から専用ブランチ
`codex/test-performance-phase1b` と専用worktreeを作成した。
元の未コミット作業には変更を加えていない。[Phase 1-A](test-performance-phase1a.md)の
対象上位3クラスを中心に、テスト用fixtureの準備を最適化する。
本番処理、検証件数、timeout、ヒープ上限、fork/並列設定を変更しない。

環境はWindows 11 Pro、Intel Core i9-12900（16コア/24論理CPU）、
メモリ約127.75 GiB、OpenJDK 25（25+36-3489）、Maven Wrapper 3.9.14、
Surefire 3.5.6、JUnit 6.0.3、sqlite-jdbc 3.51.3.0、Git 2.55.0.windows.5、
1 fork / reuseForks=true、Jupiter並列無効。
前後で同じ依存キャッシュを使い、Mavenを並行実行せず、各run間でcleanしない。
常駐アプリを停止して負荷を完全に排除した実験ではなく、単一ホストでの実測である。

最初に無変更のmainでfullを実行し、全testcase識別子・成否・skipを記録した。
対象クラスは共通の任意計測プローブとfixture抽出を適用し、コンパイルを済ませてから
それぞれ3回測定する。成功したrunだけを比較に使用する。
初回コンパイルを含むfullのwall-clock差を、そのまま性能改善率にはしない。

## 計測の境界

対象3クラスだけにJUnit extensionを登録し、
system property `rei.test.fixture-timing=true` のときだけ診断を出す。
通常の回帰テストでは無効で、時計・出力先・contextも参照しない。

- **BeforeEach準備**：BeforeEachCallbackからBeforeTestExecutionCallbackまで。
  SelfPatchReviewGitTestのリポジトリ準備を含む。
- **テストメソッド**：BeforeTestExecutionCallbackからAfterTestExecutionCallbackまで。
  インラインfixtureは名前付きscopeでも測定し、この時間の内数として扱う。
- **後始末**：AfterTestExecutionCallbackからTestWatcherの結果通知まで。
  AfterEach処理、context resourceのclose、TempDir cleanup等を含む。
  ファイル削除だけの純粋な時間と同一視しない。
- **BeforeAll準備**：共有seed初期化をinterceptorで別計測する。
  最適化の比較ではper-test準備に一度だけ加算する。

TempDir作成やクラス単位resourceの後始末など、境界外の処理もあるため、
区間の合計をMaven wall-clockそのものとは扱わない。
resource close後にTestWatcherが呼ばれることは
[JUnit 6.0.3のAPI契約](https://docs.junit.org/6.0.3/api/org.junit.jupiter.api/org/junit/jupiter/api/extension/TestWatcher.html)
に基づく。qualified classとJUnit UniqueIdでparameter invocationを保持する。
境界、名前付きscope、無効時の無干渉性を単調時計の契約テストで検証する。

既存のMaven/JUnit、`measure-tests.ps1`、`test-performance-report.ps1`を使用し、
独自runnerや選別エンジンは追加しない。

```powershell
./scripts/measure-tests.ps1 -Label before-target1 -Repository '<dependency-cache>' `
  -MavenArguments @('test','-Pfull','-B',
    '-Dtest=TaskSourcePaginationTest,StorageEventMigrationTest,SelfPatchReviewGitTest',
    '-Drei.test.fixture-timing=true')
# before/afterでラベルを変え、それぞれ3回。コンパイル用runは別にする。
./scripts/measure-tests.ps1 -Label after-full -Repository '<dependency-cache>' `
  -MavenArguments @('test','-Pfull','-B')
```

## 調査結果・変更内容・TDD

### TaskSourcePaginationTest

fixtureをテスト本体から抽出して計測し、準備中の既存repository API呼び出しだけを
3つの外側トランザクションにまとめた。goal/schedule各270件、checkpoint 1,005件、
dependency 270件の生成・終端状態への遷移は同じ。実SQLiteファイルを使い、commit後に
元のページング検証へ進む。ページサイズ100/47、総数1,545/270、重複なし・ソート済みのassertionは維持した。

新しい独立契約では別接続からcommit済み行が見えること、準備失敗時に部分行が残らないこと、
トランザクションがスレッドへ残らないことを検証した。個別commitのままではrollback後の行数が
2件となるRedを確認し、1件だけ残るGreenへ修正した。synchronousやjournal mode等は変更していない。

### StorageEventMigrationTest

10万件のJSONL生成ループで毎回`EventJsonCodec.mapper()`を作っていた。
同じ設定のmapperをループ外で1個作って再利用するfixtureリファクタとした。
生成件数、全フィールド、typed payload、ナノ秒の日時、ストリーミング書き込みを維持する。
既存の64MiB子JVM、180秒の上限、10万件のimport・bounded page確認・元ファイルhash不変の検証はそのまま。
新しい失敗assertionを人為的に作らず、準備時間の測定を性能上のRedとし、既存のmigration契約の成功を保つ。
本番のcodecやmigration実装は変更していない。

### SelfPatchReviewGitTest

12ケースそれぞれで4つのGitコマンドを実行していた初期化を、一度の実Git seed生成と
各ケースへの通常ファイルコピーに置き換えた。hardlink・alternate object store・共有indexは使わない。
各ケースは従来どおり独立したTempDir、作業ファイル、設定、index、履歴を持つ。
同じtest bodyで実Git/実シェル・timeout・切り詰め・承認・修復を引き続き検証する。

コピーだけの最初の案では、関連34件のうち既存4件がindex不変assertionに失敗した。
indexにはファイルのctime/mtime等のstat cacheが入るため、コピー先の情報に対応させる必要があった。
[Git index形式](https://git-scm.com/docs/gitformat-index)と
[update-index --refresh](https://git-scm.com/docs/git-update-index#_using_refresh)の契約に基づき、
コピー完了時に`git update-index --refresh`を1回実行する。元のindexバイト不変assertionは変更しない。
準備用Git起動は48回から16回（seed 4回 + コピー後のrefresh 12回）になる。
テスト本体のGit/シェル起動を減らした数ではない。

新しいコピー契約は、両コピーのstaged entriesが元と同じであることと、片方のcommit/設定/ファイル変更が
元・もう片方のHEAD/index/設定/ファイルを変えないことを検証する。
新規契約の「元indexとコピーindexのバイト同値」はstat cacheまで同値を要求する過剰な条件だったため、
staged entries同値と独立側indexのバイト不変に改めた。既存12ケースのassertionは維持している。

### Red / Greenと除外したrun

- 計測API未実装のcompiler Red → 時計を差し替えた2契約がGreen。
- DB rollbackとコピー未実装の2件failure（error/skipなし） → 最小実装後2件Green。
- コピーのstat cacheで既存4件failure → fixtureのrefresh後、関連6クラス34件がすべてGreen。
- 失敗run、コンパイルを含む準備用runは性能比較の成功runに含めない。
- 共通計測・fixture抽出は`3949179d`、最適化の測定対象は`ec7eda2bf8abfbb7dded5d3d03c6048fb5c7c3f9`。

## 対象30ケースの前後比較

| Run | wall秒 | suite秒合計 | 件数 |
|---|---:|---:|---:|
| p1b-before-target1 | 135.937 | 129.382 | 30 |
| p1b-before-target2 | 130.930 | 124.963 | 30 |
| p1b-before-target3 | 131.399 | 125.447 | 30 |
| p1b-after-target1 | 92.966 | 87.237 | 30 |
| p1b-after-target2 | 94.144 | 88.697 | 30 |
| p1b-after-target3 | 94.162 | 88.283 | 30 |

wall中央値 131.399 → 94.144秒、28.352%短縮。
比較に用いた6runは30件すべて成功・skipなしで、JUnit UniqueId集合も一致した。

| Class | ケース | 主fixture準備 前→後（秒） | 準備短縮率 | メソッド残り 前→後（秒） | 後始末 前→後（秒） | suite 前→後（秒） |
|---|---:|---:|---:|---:|---:|---:|
| application.task.TaskSourcePaginationTest | 2 | 25.324 → 2.235 | 91.174% | 17.370 → 18.453 | 0.018 → 0.017 | 42.765 → 20.769 |
| storage.StorageEventMigrationTest | 16 | 14.415 → 0.319 | 97.789% | 23.181 → 22.294 | 0.378 → 0.305 | 38.053 → 22.993 |
| core.SelfPatchReviewGitTest | 12 | 4.399 → 1.797 | 59.149% | 41.202 → 42.113 | 0.463 → 0.460 | 45.923 → 44.321 |

各値は3runのクラス合計の中央値で、列ごとの中央値を足してwallに一致させるものではない。
主fixture準備はBeforeEach + 名前付きinline fixture + BeforeAllであり、Git seed生成も含む。
StorageEventの名前付き区間は10万件JSONL生成で、他ケースの少量の入力準備はメソッド残りに含む。
残りには本来の検証・assertion・実子JVM・小規模な入力準備等が含まれる。
対象3クラスは元からintegrationであり、本Phaseの短縮をfastの高速化として計上しない。

## 全件の検証範囲と回帰結果

| Run | 件数 | 成功 | skip | suite / 実行suite | wall秒 | suite秒合計 |
|---|---:|---:|---:|---:|---:|---:|
| p1b-before-full | 7210 | 7202 | 8 | 1266 / 1261 | 962.867 | 857.231 |
| p1b-after-full | 7214 | 7206 | 8 | 1269 / 1264 | 1002.945 | 973.640 |
| p1b-after-fast | 4255 | 4254 | 1 | 800 / 799 | 191.671 | 182.234 |

すべてfailure/errorは0件。変更前fullの7,210件が変更後fullにすべて残り、
識別名・成否・skip理由の変化は0件、新規はfixture契約2件と計測契約2件の計4件だった。
parameter/dynamic/jqwikの識別子を保持して重複を拒否し、対象30件も前後6runで一致した。
fastの全ケースはfullに含まれ、結果も一致した。
既存の8skipは[Phase 1-Aの許可リスト](../config/test-expected-skips.json)と識別名・理由を照合した。
既存成功→skipの変更、想定外のskip、skipされた識別子の欠落はなかった。
通常のfull/fastログに計測プローブの出力は0件だった。
既存のPowerShell集計・集合検証の契約テストも成功した。

full wallは962.867→1,002.945秒、suite合計は857.231→973.640秒で、
**今回の単回full測定では全体短縮を確認していない**。
対象3クラスのfull内suite合計は104.185→81.260秒に減ったが、他suiteを含む全体の変動は大きかった。
前者は初回コンパイルを含み、full全体の変動要因は未分解である。
同条件の対象30件で確認した28.352%短縮を、full全体の改善率として転用しない。
fastは回帰確認用の1runで、本Phaseの速度改善率の比較対象ではない。

測定値・phase定義・追加ケース・skip理由・識別子digestは
[集計JSON](test-performance-phase1b-summary.json)に保存した。

## 自己レビュー・限界・次の優先順位

差分の自己レビューでは本番コード・POM・既存タグを変更していないことを確認した。
共通計測の固定点と比べ、TaskSourceの2つ、SelfPatchReviewGitの12の既存テストメソッド本体が
完全一致することを機械的に確認した。StorageEventの最適化差分はmapperの生成位置だけである。
新しいfixture契約2件はintegration、計測プローブ契約2件はfastとし、既存の条件付きskipは増やさない。

主fixture準備は大幅に減った一方、検証本体の残りには大きなコストがある。
TaskSourceではページング処理等が中央値18.453秒、SelfPatchReviewGitでは実Git・シェル検証等が42.113秒、
StorageEventではmigration・子JVM・他の入力準備等が22.294秒残る。
本体の差をfixture最適化の効果として一括して説明しない。
Gitのsuite全体は45.923→44.321秒の小さな改善であり、準備の59%短縮と区別する。
後始末は元から小さく、主要な改善対象ではなかった。

次は以下を優先する。

1. 他の重いintegration fixtureでも、検証対象が個別commit/クラッシュ復旧そのものかを確認してから、
   準備時の重複commitやcodec生成を削減する。一括置換やDB durability設定の変更は行わない。
2. TaskSourceのページング本体、StorageEventの実migration、Gitの実プロセス起動を別々にprofileする。
   実装を変える場合は、本番契約・境界条件・復旧動作を検証する別のTDD作業として扱う。
3. TIAは引き続きshadow運用から検討し、全件回帰と照合して取りこぼしを測る。
   今回は選別実行・並列化・独自runnerを導入していない。

全件測定は各1回、対象クラスは各3回であり、統計的な保証や異なるマシンでの速度保証ではない。
計測用の生ログ・XML・JVM引数・一時ファイルのパスはGitに追加せず、集計値と検証結果だけを保存する。
