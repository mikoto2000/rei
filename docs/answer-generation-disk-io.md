# 回答生成中のディスク I/O

## チェックポイント参照登録（2026-10-31）

同一チェックポイント内の参照先は重複を除き、状態照会・参照追加・改訂更新のSQLを
それぞれ1回だけ準備して再利用します。利用可能性は各参照について同じ保存トランザクションで
確認し、利用できない参照があれば全体をロールバックします。未登録の参照先の保護、
改訂ごとの所有者、重複呼び出し時に改訂を増やさない規則は維持します。

## Raw結果の容量集計（2026-10-31）

保存形式22では、未PURGEDのRAW_RESULTだけを含む容量索引を追加します。容量の定義と
上限判定は変えず、大量のイベント行を集計のために読みません。状態・サイズ変更や削除は
SQLiteが同じトランザクション内で索引に反映します。既存DBは従来のバックアップ・検証・
移行ゲートで更新し、通常のツール実行中に索引を作成しません。

## イベント採番SQLの統合（2026-10-31）

`SqliteProjectAgentEventStore` の採番用SELECTと後続UPSERTを、1回の
`UPSERT ... RETURNING` に統合した。同じトランザクション内でProjectごとの
連番を取得し、イベント／参照情報／診断ログの共同確定を続ける。
イベント保存に失敗すれば採番もrollbackし、64 bit整数の上限では加算を拒否する。

20個の本文・思考差分を実際のSpring配線から保存する再現テストでは、採番用の
SQL statement準備回数を40回から20回へ削減する。FULL COMMITは20回、
診断ファイルforceは21回のままで、消費側からは両保存先への確定後にだけ参照できる。
SQL回数の削減と、ディスク書き込み回数や実LLMの速度改善率は区別する。
並行Project、採番上限、保存失敗、割込み、強制終了後の復旧も関連テストで検証する。

mainの先行変更との統合後、ページ配置の移行番号は**version 18**。
version 17は先行するBriefing移行に割り当てられている。
以下の計測時のversion 17は統合前の検証用JARであり、同じ1 KiB配置を使う。

2026-10-26追記：現在のproject付き`MESSAGE_DELTA` / `THINKING_DELTA`は、
イベントと診断ログの管理情報を同じFULL COMMITで確定する。
以下の「計2トランザクション」は当初の保存経路であり、末尾の共同確定の節も参照。

## 保存経路と原因

`ChatExecutionService` は回答断片を `MESSAGE_DELTA`、思考断片を
`THINKING_DELTA` として即時 publish する。LLM の開始・最初の token・完了・失敗は
`AgentEventChatModel`、ツールの開始・完了・失敗は `ToolEventCallbackDecorator` が発行する。
`InMemoryAgentEventBus` が Shell / Web SSE / Native の購読者と永続化の購読者へ配送する。
プロジェクト所有イベントは `ProjectAgentEventSubscriber` →
`SqliteProjectAgentEventStore.append` → `StorageDatabase.transaction` を通る。
イベント、プロジェクト連番、参照メタデータを同じトランザクションで確定する。
重要な `publishBoundary` は保存の失敗を呼び出し元へ返し、配送前に確定する。
通常の listener 例外は既存 bus がログへ記録する。この既存の違いは変更していない。

診断経路は `ProfileEventLogSubscriber` → `ProfileEventLogStore` →
`ManagedActivityLog.append`。本文を長さ情報へ置換した JSONL を追記・force し、
ファイル長・SHA-256 チェーンを別の SQLite トランザクションで更新する。
これは生のイベント履歴とは別の、保持・改変検出付き診断情報である。
通常1イベントにつき計2トランザクションと、少なくとも1回の診断ファイル force がある。
新規 segment の作成でも force する。ローテーションは4 MiBまたは1日。

問題はトランザクション数だけではない。`StorageDatabase` は処理ごとに接続を開閉する。
他の接続がなければ、WAL に追記した直後に最後の接続が閉じ、チェックポイントと
WAL / SHM の削除が発生する。次の保存で再作成し、同じページを書き直す。
SQLite の既定動作は [WAL の公式説明](https://www.sqlite.org/wal.html)を参照。
初期テストでも、context 稼働中に COMMIT 直後の WAL が消えていた。

会話本文はストリーム中 `StringBuilder` に蓄積され、会話ログの assistant 本文は
結果確定後に保存される。ここで回答全文の token ごとの UPDATE は見つからなかった。
Checkpoint は開始、ツール結果、終了等の別の復旧境界で保存される。
Run / Goal / Scheduler / Waiting / Dependency は専用 repository を使い、
イベント連番による永続 replay と Checkpoint / 承認の確定は維持する。
`memory.db`、`memory-consolidation.db`、`vectorstore.db` 等の既存 DataSource は変更しない。
これらの接続設定は SQLiteDataSource 等に委ねられ、storage.db の設定変更ではない。

`logback-spring.xml` は INFO の RollingFileAppender、10 MB / 日ごとの gzip rotation、
14日・合計200 MBを設定する。AsyncAppender はなく immediateFlush は既定値。
Bluesky に DEBUG の個別設定があるが、回答 delta ごとの SLF4J ログは確認していない。
今回の模擬ストリームは通常の Activity 保存を測定し、Logback の rotation / 圧縮は誘発しない。
LLM Request Capture は `CaptureStore` の有界なメモリ保持であり、通常の delta ファイル保存ではない。
診断本文の長さ置換と既存の secrets の扱いを変えない。

## 採用した変更

startup gate が migration / schema 検証後、データディレクトリごとに1個の
SQLite 書き込み接続を保持・再利用する。既存の writer monitor で全書き込みを直列化し、
各 callback を独立して COMMIT / rollback する。SQL statement と read snapshot は保持しない。
JDBC は COMMIT / rollback 後に空の BEGIN DEFERRED を用意するが、読み取りを開始するまでは
snapshot / read lock はなく、別接続による checkpoint / VACUUM を妨げないことを結合テストで確認する。
短寿命接続の開閉・PRAGMA 初期設定・ページキャッシュ再作成もストリームごとに繰り返さない。
パスの安全性と schema version は各 transaction でも検証する。
共有 context の参照数がゼロになった時点で保持接続を閉じ、次に migration のプロセス lease を解放する。
初期化失敗時も context の disposable lease で解放する。
複数 Run の数に比例して保持接続やメモリキューが増える設計ではない。
ネストした transaction は外側の部分データを誤って COMMIT しないよう拒否する。
rollback が失敗した接続は閉じて破棄し、元の例外に cleanup 失敗を付加する。
次の保存は同じ lifetime lease の下で新しい接続を取得する。
Spring 外の standalone constructor は従来の短寿命接続を使う。

`journal_mode=WAL`、書き込み接続の `synchronous=FULL`、
`wal_autocheckpoint=1000`、busy timeout 5000 ms、schema version 5 は維持する。
自動チェックポイントと既存の明示メンテナンスも維持する。
WAL は稼働中に残るため、ファイルが残ること自体は未確定データを意味しない。
通常終了では最後の接続が閉じる。異常終了では確定済み WAL を次回 SQLite 接続で復旧する。
WAL を手動削除したり、稼働中の DB 本体だけをバックアップしてはならない。
既存のオンライン backup / restore 経路を使う。

## 採用しなかった候補

- 500 ms / 64 KiB / 100件の遅延保存：細粒度イベントには永続 replay / 参照情報がある。
  メモリだけで遅延すると異常終了直前の末尾を失うため、既存保証を保つ今回の変更には入れない。
  MESSAGE_DELTA / THINKING_DELTA は将来の集約候補だが、ID・sequence・参照・再開契約の設計が先に必要。
  Run / Goal / ツール結果 / 承認 / Checkpoint / Scheduler / Waiting 等の境界もすべて従来どおり即時保存する。
- バッチ COMMIT：同期のイベント1件ごとの確定を、次の未発生イベントと一緒に確定することはできない。
  強制終了時の損失を許容する仕様、または別の耐久性付き journal が必要。
  COMMIT 80%削減目標はこの対策では達成しない。
- AsyncAppender / 保存ワーカー：書き込み回数を自動で減らすものではなく、重要な保存失敗の伝播も複雑にする。
  今回は永続化用キューを追加しない。時間・件数・サイズ上限・flush retry の新設定も不要。
- Activity の削除 / イベントからの再構成：独立した保持・改変検出の目的があるため削除しない。
- synchronous=NORMAL / OFF、autocheckpoint 停止：耐久性を下げたり、WAL の無制限増加を招くため採用しない。
- Defender 無効化、除外設定、Indexer / 同期ソフトの停止：一切実施しない。

## 計測の再現方法

JDK 25 と Maven の依存キャッシュを用意して、Windows の通常のターミナルで実行する。
性能カウンターの読み取りが制限された環境では OS 値を取得不能として記録する。
OS の `Get-Counter '\PhysicalDisk(*)\% Idle Time'` 等で対象 SSD の instance を確認する。

```powershell
.\scripts\measure-answer-generation-io.ps1 -DiskInstance '0 C: F:' -EventsPerSecond 50
```

instance は今回の PC の例であり、既定 `_Total` は全物理ディスクの集計。
各 fixture は `target/answer-io-temp` 内の一時 SQLite / 一時 Activity ログだけを使う。
実 LLM、ユーザー DB、プロンプト、認証情報は使わない。
短文20イベント、長文1,000イベント（各32文字、前半思考）、高頻度1,000イベント（各1文字）、
4 Run × 250イベントの並列を各3回実行する。
既定の50イベント/秒は Run 全体の入力頻度で、並列でも4 Run合計50件/秒。
単調時計による同一の到着スケジュールまで待機してから発行する。遅れた場合は取り戻すため、
遅い保存処理の時間やバックプレッシャーを隠さない。時間の長さ自体をテストの合否には使わない。
入力設定はベンチマークだけに作用し、本番の token 配信を制限しない。
`-EventsPerSecond 0` は最大速度のストレス試験。0 または10〜10,000を受け付ける。
baseline は以前の startup と同じく migration のみ、optimized は本番 startup gate を使う。
イベント保存・診断保存・表示購読者は共通である。

JDBC の実際の setAutoCommit(false) / commit 呼び出しをテスト Driver で観測する。
任意の thread-local `StorageIoObservation` は成功した明示 force、Activity の追記操作・バイトを
メモリ内で数える。本番で観測 scope を作らない場合は計測ファイルを出力しない。
永続化平均・P95 は2保存先の各 append、publish 平均・P95 は配送完了までの処理時間。
publish P95 は並列時の bus 待機も含む。実 UI / SSE 通信の表示遅延の直接測定ではない。
Windows GetProcessIoCounters の write operations / bytes は測定 JVM 全体の OS 指標で、
SQLite の native I/O も含む。ただし他の JVM スレッドやファイルも含み、SSD の物理書き込み量ではない。
heap は100 msごとのサンプル最大であり、厳密なピーク・RSSではない。
結果 JSON は計測区間が終わってから出力する。

OS の SSD アクティブ時間は100 ms周期の `100 - PhysicalDisk % Idle Time` を別途採取する。
最大速度モードでは高速化によってイベント/秒も増えるため、アクティブ率だけで
同一入力頻度における改善を判定しない。このモードの結果も隠さず保存する。
他プロセスの I/O も含み、実 LLM 接続での改善を直接保証する測定ではない。
カウンターとそのデータ状態を両方検証し、取得不能時は理由とサンプル数ゼロを記録する。
[Microsoft の Idle Time 定義](https://learn.microsoft.com/en-us/previous-versions/aa394308(v=vs.85))と
[PDH 状態コード](https://learn.microsoft.com/en-us/windows/win32/perfctrs/pdh-error-codes)を参照。
SQLite 内部の fsync 総数、実際の暗黙 / 自動 checkpoint 総数、SSD 物理書き込み量、
実 GUI 表示・Run 完了レイテンシはこの fixture では未計測。
アプリ force 回数を SQLite fsync 回数と混同しない。

## 計測結果と検証

2026-10-11 JST、OpenJDK 25+36、Maven Wrapper 3.9.14、SQLite JDBC 3.51.3.0。
ベース commit は `a2afda4ef1306fe0a8da5bf2dcc9b6d82e285ad1`。
対象は物理ディスク0、Samsung SSD 990 PRO with Heatsink 4TB（C: / F:）。
Defender (`MsMpEng`) と Search Indexer は稼働したまま。
Procmon は PATH で検出できなかった。WPR / WPA はあるが実行トークンは非管理者で、
ETW のプロセス別・ファイル別トレースは採取しなかった。既存 WPR recording もないことを確認した。
通常環境の PDH は取得できたが、sandbox では `PDH_CANNOT_READ_NAME_STRINGS` になった。
最終の OS 計測は同じ通常環境で行う。

### TDD と設計の絞り込み

1. WAL が context 稼働中に残るテストと共有 context の所有者テストを追加し、2件の assertion failure を確認した。
2. lifetime 接続を追加し、4件の lifecycle / rollback / maintenance / 異常終了テストが成功した。
3. 接続を保持するだけの初期案は最大速度条件で SSD アクティブ率を約60%減らしたが、
   永続化・配送 P95 は一部悪化した。結果は [anchor-only.json](answer-generation-io-data/anchor-only.json) に残す。
4. 書き込み接続の再利用と各 callback の即時確定を検証するテストを追加し、期待した1件の assertion failure を確認した。
   実行中の全体テストに影響させないため、Red のビルドは同じ worktree の target 内のソースコピーで行った。
5. writer を共有し、rollback 失敗・ネスト拒否・通常/キャンセル/エラー終端・project 分離・
   起動失敗・自動 checkpoint を含む12件と観測テスト1件、性能テスト1件が成功した。
6. 最大速度では永続化・配送が速くなる一方、より短い時間に I/O が集中してアクティブ率が増えた。
   入力条件の比較を明確にするため50イベント/秒の試験も追加する。

### 最大速度の結果

各3回の中央値。件数 / bytes の OS 値は測定 JVM 全体。時刻は baseline 00:34〜00:37、
再利用案 00:54〜00:55 JST。ストリームの4倍近い高速化に伴い、SSD アクティブ率の平均は
増えた。この条件で平均アクティブ率30%削減は未達であり、逆の結果を改善として扱わない。

| ケース | 書き込み回数 前→後 | 書き込み bytes 前→後 | append P95 ms 前→後 | 配送平均 ms 前→後 | SSD平均 % 前→後 | SSD P95 % 前→後 |
|---|---:|---:|---:|---:|---:|---:|
| 短文 | 759→487 | 1,921,405→965,789 | 11.87→4.25 | 18.25→5.45 | 68.17→75.85 | 70.44→75.85 |
| 長文 | 43,692→28,911 | 111,801,603→59,329,731 | 11.36→3.89 | 18.93→5.11 | 71.21→81.23 | 91.74→83.05 |
| 高頻度 | 43,629→28,853 | 111,626,067→59,174,675 | 11.41→3.92 | 19.30→5.05 | 71.06→81.04 | 92.02→82.95 |
| 並列 | 44,589→29,817 | 114,254,845→61,449,285 | 11.10→3.93 | 73.82→20.35 | 70.81→81.89 | 86.35→83.50 |

生データは [maximum-baseline.json](answer-generation-io-data/maximum-baseline.json) と
[maximum-optimized.json](answer-generation-io-data/maximum-optimized.json)。
COMMIT は短文40、その他2,000で変化なし。明示 force は21 / 1,001で変化なし。
保存前の connection open は40 / 2,000から0へ減少（初期保持接続1件は計測区間外）。

### 同一入力頻度の結果

01:11〜01:17 JST。各3回の中央値。全 Run 合計50イベント/秒。
生データは [baseline.json](answer-generation-io-data/baseline.json) と
[optimized.json](answer-generation-io-data/optimized.json)。全指標・変化率・試行間の範囲は
[比較表](answer-generation-io-data/comparison.md) に保存した。

| ケース | SSD平均 % 前→後 | 変化率 | SSD P95 % 前→後 | 変化率 | append P95 ms 前→後 | 配送 P95 ms 前→後 |
|---|---:|---:|---:|---:|---:|---:|
| 短文 | 61.43→22.12 | -64.00% | 61.85→22.86 | -63.04% | 12.04→5.03 | 20.50→8.88 |
| 長文 | 65.79→21.90 | -66.71% | 85.99→26.64 | -69.02% | 10.96→4.64 | 20.64→7.40 |
| 高頻度 | 66.48→21.15 | -68.18% | 86.29→24.88 | -71.16% | 10.76→4.47 | 20.40→7.08 |
| 並列 | 67.49→21.49 | -68.16% | 89.49→25.30 | -71.73% | 10.44→4.47 | 102.27→7.18 |

この入力条件では OS アクティブ率の平均・P95 の30%削減目標を達成した。
書き込み操作は約34〜36%、書き込み bytes は約47〜50%減少した。
COMMIT / 明示 force は即時確定を維持するため変化なしで、COMMIT 80%削減目標は未達。
保持された WAL は短文約0.96 MB、1,000イベントでは約4.18〜4.19 MB。
終了前のサンプルであり、ファイルサイズを自動 checkpoint の厳密な上限として扱わない。
SQLite は大きい transaction や長い reader があると閾値を超えることがある。
保持接続自体は read snapshot を持たず、自動 checkpoint の書き戻しと明示メンテナンスをテストした。

## 回帰確認と未実行範囲

- 最終実装の `mvnw.cmd -o -B -Pfull test`：4,670件、失敗0、エラー0、スキップ2（成功4,668）。
  うち1件は opt-in の性能テストで、別途 baseline / optimized の両方式を実行済み。
  もう1件は PlantUML の実 jar 未設定による既存 renderer テストのスキップ。
- `npm test`：25ファイル、99件成功。既存 lockfile を offline / ignore-scripts で復元し、依存を更新していない。
- 新規 correctness：writer lifetime / 再利用 / 独立確定 / rollback / ネスト拒否 / 故障からの再取得 /
  共有 context / 起動失敗 / 自動 checkpoint / メンテナンス / 強制プロセス終了・再起動 /
  完了・キャンセル・エラー / ツール境界・project 分離・順序・本文を含まない診断情報。
- 既存全体回帰には Chat、Shell、HTTP/SSE、Planning Loop、Run/Goal、Scheduler、Waiting/Dependency、
  Checkpoint Resume、Notification、SubAgent、外部 agent 委譲、LLM Request Capture、Session/Project 履歴を含む。
- 実 LLM / 実 Codex・Claude Code サービス、Native GUI の目視・表示時間、電源断は未実行。
  Fake LLM / fake 外部プロセス・既存 client テストと、異常終了した子 JVM の WAL 復旧までを検証した。
- flush 間隔・件数・サイズ・保存キュー上限のテストは、新しい遅延保存 / キューを導入していないため該当しない。
  UI と保存済みイベントの単位・順序・ID・sequence・payload と schema version は変更していない。

## 残課題

最大速度でイベントを供給し続ける場合、短い時間に多数の FULL COMMIT / Activity force が集中し、
アクティブ率は高くなりうる。実際に最大速度モードの平均率は増加した。
入力が50件/秒より速いケースや実 LLM の chunk の到着間隔でも再評価する必要がある。
この最適化は不要な WAL teardown と接続再作成を減らすもので、確定済みデータの耐久性を下げない。

COMMIT / Activity force 自体をさらに減らすには、イベントの永続 replay / 参照と独立した診断保持を
維持する durable journal / group commit の設計が必要である。将来の時間集約は
未保存 delta の損失許容範囲を明示してから検討し、今回のコードで黙って導入しない。
Defender / Indexer 等の寄与、SQLite 内部の fsync / checkpoint 総数、Native の描画遅延の切り分けは
権限を持つ実機での ETW / Procmon と実際のストリーム再現が今後の計測候補。

## 追加改善：読み取り接続と進んでいない再生位置

`StorageDatabase.read` もアプリケーションの起動 lease 内で読み取り専用接続を再利用する。
データディレクトリごとに待機接続を最大1個保持し、同時読み取り・再入時は空きを待たず
従来どおり別接続を開く。貸し借り用のロックはSQL実行中には保持しない。
接続のページキャッシュを再利用するが、検索結果自体はキャッシュしない。
各callbackはStatement / ResultSetを閉じ、autocommitのまま返る。
接続を再利用する場合もパスとschema versionを確認し、失敗した接続や明示transactionに
切り替わった接続は閉じる。最後のcontext終了時には待機readerをwriterより先に閉じる。
貸出中にアプリケーションの所有leaseが入れ替わった接続は次のleaseへ持ち越さない。
standaloneで起動leaseがない場合は従来どおり毎回閉じる。

`SqliteEventCursorStore.compareAndSet` は再生位置が同じ場合、UPDATEではなく単一のSELECTで
既存行・sequence・generation・converted・保存済みイベント末尾を照合する。
カーソル未作成、世代変更、末尾を超える位置を成功扱いにしない。
進む場合は従来の条件付きUPDATEと即時COMMITを維持する。
同値UPDATEはSQLite自体が物理書き込みを省ける場合もあり、この変更による
SSD書き込みbytesの削減量は主張しない。不要な書き込みtransactionとwriter lock取得を避ける改善である。

読み取り計測は1,000行の一時DBに対する同じcount queryを各方式1,000回、交互に3回行う。
起動・migration・fixture作成・warmupは計測区間外にする。
`StorageReadReuseTest#measureRepeatedReads` を
`-Pfull -Drei.io.read-benchmark=true` で明示実行し、結果を
`target/io-read-reuse.json` に出力する。
Windows GetProcessIoCountersによるプロセス全体の読み取り量であり、OS cacheを含む
ファイル読み取りとSSDの物理読み取り量は区別する。
更新のないDBの繰り返し読み取りなので、実会話中の負荷全体の削減率としては扱わない。

2026-10-26の最終コードによるWindows実行の3回の中央値は次のとおり。
生データは [read-reuse.json](answer-generation-io-data/read-reuse.json) に保存した。

| 指標（1,000回のquery） | 毎回接続を開閉 | 読み取り接続を再利用 |
| --- | ---: | ---: |
| プロセス読み取りbytes | 62,279,368 | 0 |
| プロセス読み取り操作数 | 16,216 | 0 |
| 所要時間 | 6,678.30 ms | 71.14 ms |
| プロセス書き込みbytes | 0 | 0 |

0はwarmup済み・更新なしの計測区間の値であり、起動や更新検知に一切読み取りがないという意味ではない。
`StorageReadReuseTest`の6件は、外部COMMITの反映、読み取り専用、snapshotを残さないこと、
checkpoint / VACUUM、並列・再入、失敗・明示transactionの破棄、schema再検証、
lease切り替え、カーソルの未作成・世代・末尾検査とI/O計測を検証する。
既存の`StorageWalLifetimeTest`12件も成功し、個別COMMITと異常終了後の復旧を維持した。

全体の`-Pfull verify`は6,384件、失敗0・エラー0・既存skip 5でテストが完了し、
`target/rei-io-reduction.jar`を生成した。既存の起動用JARは上書きしていない。
skipはWindows Codex 2件、WSL Codex 1件、実PlantUML 1件、既存の回答生成I/O benchmark 1件。
今回の新規6件はskipなし。Checkstyleは違反0、変更したJava 3ファイルのSpotlessも成功した。
全体Spotlessは変更していない既存5ファイルの整形違反により失敗したため、
後続goalを含むMavenコマンド全体はBUILD FAILUREとなった。
対象は`ConversationLifecycleAdvisorTest`、`ExternalAgentReviewAdvisorTest`、
`memory/package-info.java`、`DurableSubAgentRepositoryTest`、`testsupport/package-info.java`。
検証ログは`target/io-reduction-full.log`、変更ファイルの整形確認は
`target/io-reduction-format-check.log`。実行時のみMockitoを起動時javaagentに指定し、
tempをリポジトリ内に隔離し、ローカルHTTP通信が可能な環境で実行した。

書き込みの`synchronous=FULL`、イベントごとのCOMMIT、Activityのforce、
保持期間と保存形式は変更しない。回答断片ごとの同期書き込み負荷は引き続き残る。

## 追加改善：ストリームの共同確定

`StreamingEventPersistence`はproject付きの回答・思考deltaを、通常配送前の永続化段階で保存する。
既存の診断adapterである`ProfileEventLogStore.persistStreamingEvent`と`ManagedActivityLog.appendWith`が
同じ保存ルートであることを確認し、一つの`StorageDatabase.transaction`で次を行う。

1. 元のイベント本文・project連番・参照メタデータを登録する。
2. 本文を長さに置換した既存形式の診断JSONLを追記し、従来どおりforceする。
3. 診断segmentの長さ・SHA-256チェーンを更新する。
4. `synchronous=FULL`でCOMMITし、その後にreplayと通常購読者へ配送する。

同じイベントを通常の履歴・診断購読者が再保存しないため、deltaごとのCOMMITは2回から1回になる。
イベントを時間や件数で遅延保存せず、トップレベルのpublishが正常に戻った時点で本文と管理情報の両方が確定する。
再入publishは従来どおり外側の配送ループで処理し、各イベントはその配送前に確定する。
JSONLのforce・4 MiBまたは1日のrotation・改変検出・参照保護・既存の保持処理は維持する。
Run開始・完了・失敗・キャンセル・Tool・承認・Checkpoint等は従来の保存境界を使う。
明示ファイルの診断出力、旧ファイルイベントstore、projectなしのイベントは従来の経路を使う。
JSONLやイベントrecordの保存形式は維持する。DBのページ配置変更は後述のversion 18移行で行う。

永続化段階の例外は発行元へ伝え、失敗したイベントをreplay・通常購読者へ渡さない。
再入で待機した未配送イベントも、次のpublishへ持ち越さない。
通常購読者の例外をログへ記録して他の購読者へ配送する既存の動作は維持する。

JSONLの同期後・DB確定前に失敗した場合は、従来と同様に未確定の末尾を削除しない。
DBのイベント・連番・診断管理情報はrollbackされ、本文は保全される。
長さやチェーンが一致しないsegmentは読み取りと後続追記を拒否するため、
調査・復旧が必要な状態を正常な履歴として扱わない。
すでに確定・配送したイベントはDBとJSONLの先頭に残る。
この障害時の診断ログ保護を、末尾の自動削除で回避しない。

`StreamingPersistenceTest`は本番と同じSpring配線で、表示購読者を先に登録しても
両方の保存が表示前に完了すること、deltaごとのFULL COMMITが1回であること、
project分離、非stream境界、DBエラー時の非表示・非replay、確定済み本文の保持を確認する。
子JVMの`Runtime.halt`でCOMMIT後とJSONL同期後・COMMIT前をそれぞれ検証する。
共同確定の依存は既存の診断保存adapterを経由し、機能間の循環依存の予算を増やさない。

計測は次のコマンドで、writer再利用あり・共同確定なしと、writer再利用あり・共同確定ありを比較する。
同一のfixture・50イベント/秒・各ケース3回で、ユーザーデータや実LLMは使わない。

```powershell
.\scripts\measure-answer-generation-io.ps1 -StreamingCommits -EventsPerSecond 50
```

共同確定後のappend計測は一つの保存処理全体、変更前は個別の保存処理であるため、
両方式の`persistenceP95Ms`を直接比較しない。配送全体の`publishP95Ms`を比較する。
プロセス書き込みbytesはSQLiteのnative I/Oを含むがSSDの物理書き込み量ではない。
ディスク稼働率は指定した物理ディスクinstance全体の値であり、他プロセスの影響を含む。

## 追加改善：小更新によるページ書き込みの増幅を抑える

共同確定だけを適用した比較では、COMMITが半減してもプロセスの書き込み回数とbytesは変わらなかった。
そこで`storage.db`のページサイズを4 KiBから1 KiBに変更し、小さなイベントや索引の更新で
書き直すページのbytesを減らす。SQLiteがサポートするページ設定とVACUUMを使い、
独自の遅延保存journalや未保存のメモリキューは追加しない。
仕様は[SQLite page_size](https://www.sqlite.org/pragma.html#pragma_page_size)、
[VACUUM](https://www.sqlite.org/lang_vacuum.html)、[WAL](https://www.sqlite.org/wal.html)を参照。

version 18の起動移行は、他のプロセスを排除する既存leaseと検証済みオンラインバックアップの下で行う。
schema作成・通常beanの起動前にFULL同期のrollback journalへ切り替え、空き容量を確認して
VACUUMを実行し、1 KiBが適用されたことを検査する。その後WALへ戻して移行を確定する。
SQLite自身の原子的な再構築に任せ、DBファイルを手作業で置き換えたりWALを削除したりしない。

変換前後にintegrity_checkを行い、schemaの定義と全テーブルの全行・全列の型付き値を
SHA-256で照合する。BLOB、NULを含むTEXT、64 bit整数、REAL、NULL、自動採番の状態も対象にする。
物理ページ番号や暗黙rowidには依存しない。
不一致やI/Oエラーでは通常起動を停止し、検証済みバックアップを保持する。
ページ再構築の完了直後・schema更新前の中断からも、既存の移行手順で再試行できる。

書き込み接続の`synchronous=FULL`は維持し、`wal_autocheckpoint`は1 KiB × 4,000ページとして
従来の4 KiB × 1,000ページと同じ約4 MiBを目安にする。checkpointを無効化しない。
読み取り接続のautocheckpoint値は書き込み接続の設定と区別する。
既存のmemory / consolidation / vector DBは対象外。
初回起動にはバックアップと再構築の時間・空き容量が必要になる。
旧versionのJARはversion 18を開かず停止するため、旧版へ戻す場合は
[既存のバックアップ復元手順](storage-backup-restore.md)で確認済みの旧DBを復元する。

書き込みbytesが減る一方、1ページが小さいためnativeの書き込み操作数は増えうる。
このトレードオフも計測表に記録し、COMMIT数・書き込みbytes・操作数・ディスク稼働率を区別する。
計測スクリプトの`-StreamingCommits`は、4 KiB・個別確定と1 KiB・共同確定を同じ条件で比較する。

### 最終構成の計測結果

各条件3回の中央値、全Run合計50イベント/秒。短文は20イベント、他は1,000イベント。
beforeはwriter再利用済み・4 KiB・個別確定、afterはwriter再利用済み・1 KiB・共同確定。
入力payload、イベント・診断のrecord形式、FULL同期、JSONL forceは共通。
beforeのschema 16とafterのschema 17は同じ論理テーブルを持ち、version 17はページ配置の移行である。

| 条件 | 書き込みbytes 前→後 | 変化率 | native書き込み操作数 前→後 | 変化率 |
| --- | ---: | ---: | ---: | ---: |
| 短文 | 965,789→291,933 | -69.77% | 487→567 | +16.43% |
| 長文 | 59,329,731→23,963,867 | -59.61% | 28,911→44,210 | +52.92% |
| 高頻度 | 59,174,675→22,340,507 | -62.25% | 28,853→41,862 | +45.09% |
| 並列 | 60,938,213→22,325,093 | -63.36% | 29,561→41,734 | +41.18% |

| 条件 | ディスク平均稼働率 % 前→後 | P95稼働率 % 前→後 | 配送P95 ms 前→後 |
| --- | ---: | ---: | ---: |
| 短文 | 12.22→7.15 | 14.39→8.18 | 7.60→5.83 |
| 長文 | 15.22→6.96 | 31.22→8.46 | 8.35→5.62 |
| 高頻度 | 15.43→6.92 | 35.24→8.41 | 8.18→5.49 |
| 並列 | 13.87→7.04 | 34.41→8.59 | 8.38→5.64 |

COMMITは全条件で50%減（1,000イベントでは2,000→1,000）。明示forceは変化なし
（1,000イベントで1,001回）。小ページのためnative書き込み操作数は増えたが、
bytesは約60〜70%減り、長文・高頻度・並列の平均稼働率は約49〜55%減った。
稼働率は`_Total`、100 ms周期の共有ディスク指標で、他プロセスの寄与を分離していない。
短文は数サンプルしかないため稼働率の精度が低い。全ケース3回の実行結果であり、
実LLM・実GUI・実運用すべてで同じ削減率になるという保証ではない。

生データ：[before](answer-generation-io-data/stream-before.json)、
[共同確定のみ](answer-generation-io-data/stream-commit-only.json)、
[after](answer-generation-io-data/stream-after.json)、
[中央値比較](answer-generation-io-data/stream-comparison.json)。
[最大速度の1 KiB予備計測](answer-generation-io-data/stream-page-maximum.json)は
入力頻度が異なるため、上の稼働率比較には含めない。

### 先行変更統合前の回帰と適用物

最終コードの`-Pfull verify`は6,438件、失敗0・エラー0・既存skip 5
（Windows Codex 2、WSL Codex 1、実PlantUML 1、opt-in回答生成I/O benchmark 1）で成功した。
読取再利用のopt-in benchmarkは実行している。構成の依存予算は拡大していない。
Checkstyleは違反0、変更したJava 18ファイルのSpotlessも成功。
全体Spotlessの既存の未整形5ファイルは変更対象外とし、この回帰では変更ファイルを確認した。

`StoragePageLayoutTest`の4件、`StreamingPersistenceTest`の6件、既存のmigration / backup /
reference / retention / WAL crashのテストを含む。
最終JARは`target/rei-stream-io-reduction.jar`。既存の起動JARと前回の読取改善JARは上書きしていない。
実行中のReiへの差し替えや再起動、実ユーザーDBの移行はこの作業では行っていない。
初回起動時に検証済みバックアップを作成し、version 17のページ配置へ移行する。

全体実行ログは`target/stream-write-full.log`。実行時のみMockitoを起動時javaagentで読み込み、
tempとtest dataをリポジトリ内へ隔離し、ローカルHTTP通信が可能な環境で実行した。

依存関係の整理後の最終コードも最大速度モードで各条件3回を再実行し、成功した。
長文は毎回23,963,867 bytes・1,000 COMMIT・1,001 forceで、50イベント/秒時と同じ書き込み量を確認した。
[最終コードの再計測](answer-generation-io-data/stream-final-maximum.json)と
`target/stream-write-final-counter.log`を保存した。入力頻度が違うため、この追加実行の
稼働率と配送時間は50イベント/秒の比較表には混ぜていない。

### mainの先行変更との統合

main上のBriefing移行がversion 17を使用するため、ページ配置変更をversion 18へ移した。
正常なBriefing付きversion 17はそのまま保全して配置を変換する。
以前の検証用JARによるページ配置のみのversion 17も、1 KiB配置・完了したmigration-state・
既存のversion 16までのschemaが揃う場合に限って認識し、バックアップ後に不足するBriefing schemaを作成する。
認識できない状態で必須のBriefing tableが欠落していれば、従来どおり起動を拒否する。
追加の互換テスト2件を含む関連62件は失敗・エラー・skipなしで成功した。
統合後の適用物は`target/rei-stream-io-reduction-v18.jar`を使用する。
統合後の`-Pfull verify`は6,464件、失敗0・エラー0・既存skip 5で成功。
Checkstyleは違反0、変更した18ファイルのSpotlessも成功した。
検証ログは`target/stream-merge-full.log`。


### ReadingMetrics の保存待ちと入れ子処理

計測値・本文ハッシュ集合のコピーだけを共通ロック内で行い、SQLiteへのsave/loadとJSON化は共通ロック外で実行します。
64個の固定ストライプロックで同一Runの保存・復元を直列化し、古いsnapshotが後から新しい値を上書きすることを防ぎます。
衝突したRun同士は保存を待ちますが、カウンタの加算・参照や別ストライプのRunはデータベース待ちに巻き込まれません。
本文ハッシュの計算も共通ロック外で行います。復元中に別スレッドがRunを初期化した場合は、その現在値を優先します。

`ReadingMetrics.operation`が同じスレッド・Runで入れ子になった場合、各処理の回数と時間は計測しつつ、
最後のcloseで1回だけ保存します。内部で渡された非nullのstoreを保持し、例外時もスレッド内の状態を解放します。
明示的なflushは従来どおり即時保存で、保存失敗は非致命的なmetricsPersistenceFailuresに記録します。


### Activity 区間切替の参照登録

区間を閉じる際は全イベントを検証・読み取り、SESSION_HISTORY/RUN_HISTORY の組を重複除去して
同じトランザクションで登録します。対象objectの完全な復元は一度、SQL準備は3回です。
8,396イベント・16,792候補が同一Session/Runなら参照は2件だけで、再試行時のrevisionは増えません。
所有者の不正値、本文変更、利用不可object、壊れたメタデータは従来どおり拒否します。
