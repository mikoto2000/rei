# 回答生成中のディスク I/O

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
