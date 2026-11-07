# DEVELOP

Rei の開発者向けドキュメントです。利用手順は [README.md](README.md) を参照してください。

## 構成

| 場所 | 責務 |
| --- | --- |
| [ルートの Maven プロジェクト](pom.xml) | Spring Boot Backend。従来 Shell、認証付き Web API、会話受付と Run 実行、ツール、永続化を担当します。 |
| [軽量 CLI の Maven reactor](terminal/pom.xml) | `contract` は接続・コマンドの契約、`completion` は共通補完、`cli` は JLine と HTTP/SSE、`launcher` は Backend の検出・起動・停止を担当します。 |
| [Native Client](client/README.md) | Tauri 2・Rust・React・TypeScript。Web API 経由で会話履歴・Run・Workspace を操作し、SSE を画面表示に反映します。 |

Backend が Session と Run を所有します。軽量 CLI の Project / Session の選択はプロセスごとに独立し、会話の送信は Backend の受付を通ります。Backend は同じ Session での実行を排他制御し、永続 receipt の照会期間内に同じ受付キーと内容で再送されると元の Run を返します。[CLI と Backend の境界](docs/lightweight-cli-client.md)・[Native Client と Web API](docs/native-web-api.md)

パッケージの責務や会話処理の順序は [図解付きプログラム構造ガイド](docs/program-structure.md) を参照してください。

## 機能別の設計資料

明示指定時の暗黙選択省略・モデル完了待ち上限・比較方法は
[スキル選択の待ち時間](docs/skill-selection-latency.md)を参照してください。

ロングランタスクの対象網羅性・永続結果・復旧の設計と進捗は [実行基盤](docs/durable-task-execution.md)、
TDDとPhase別の検証記録は [実装記録](docs/implementation-report-durable-task-execution.md) を参照してください。

構造化 REST API ツール、認証・内部通信・承認・比較測定は [HTTP API ツール](docs/structured-http-api.md) を参照してください。

YAMLの生活設定とProject横断の朝夕レポートは [全体秘書設定](docs/global-secretary-settings.md) を参照してください。

先回り提案・準備時間・期限切れの仕様は [先回り準備](docs/life-preparation.md) を参照してください。

朝夕レポートの生成・履歴・検証は [朝夕ブリーフィング](docs/life-briefings.md) を参照してください。

勤務時間制約・生活時間の設定と検証は [生活コンテキスト](docs/life-context.md) を参照してください。

秘書の約束・タスク台帳と段階的な統合は [秘書機能](docs/secretary.md) を参照してください。

デスクトップ監視の軽量観測・設定・限界・検証は [入力状態による軽量観測](docs/activity-lightweight-observation.md)を参照してください。
前面画像認識の頻度制御・時刻診断・性能比較は [画像認識キュー](docs/activity-vision-queue.md)を参照してください。
直近5分の証拠による補足推定・LLM予算・集計への影響は [時系列作業推定](docs/activity-temporal-inference.md)を参照してください。

進捗イベント、Read範囲、局所・目標停滞、再開時の復元と復旧予算は [進捗・停滞判定ガイド](docs/progress-stagnation.md) を参照してください。

Episode の経緯・根拠・差分整理・横断検索は [Episode Memory](docs/episode-memory.md) を参照してください。

回答生成中の SQLite 接続再利用・耐久性・I/O 計測は [ディスク I/O の改善記録](docs/answer-generation-disk-io.md)を参照してください。

パッケージ依存の実測、循環、ArchUnitの既存負債予算と更新手順は [パッケージ構成改善 Phase 0](docs/architecture/README.md) を参照してください。

内部仕様・変更記録は [.kiro の実装補足](.kiro/docs/readme-implementation-notes.md)を参照してください。

ファイル読み取り・検索順位・部分編集・Java シンボル読み取りの上限、互換性と計測は [ファイル操作の改善記録](docs/file-operations-optimization.md)を参照してください。

初学者教材レビューの静的解析API・CLI・品質評価は [初学者レビューガイド](docs/beginner-material-review.md) を参照してください。

時間計測の時計・Span・保持上限・検証方法は [時間計測ガイド](docs/run-timing.md) を参照してください。

環境診断の範囲・状態・設定元の表示は [環境ドクターガイド](docs/environment-doctor.md) を参照してください。

## 開発環境

現在の基盤は Spring Boot 4.1.1 / Spring AI 2.0.1 です。接続設定と既存履歴の互換対応は [更新ガイド](docs/spring-ai-2-upgrade.md) を参照してください。

- JDK 25 以上
- Maven Wrapper または Maven
- OpenAI 互換 API へ接続できる環境
- Web 検索や Google Calendar を使う場合は対応する認証情報

従来 Shell をソースから起動します。Windows では `./mvnw.cmd` を使用してください。

```bash
./mvnw spring-boot:run
```

`build.bat` / `sh ./build.sh` は、ルートの Backend と独立した `terminal/pom.xml` の reactor を順に `package` します。既定では両方のテスト実行を省略します。`-DskipTests=false` を渡すと、ルートでは選択したプロファイル、軽量 CLI では reactor の設定に従ってテストを実行します。Native Client のビルド手順は [client/README.md](client/README.md) を参照してください。

Maven は `generate-sources` で `src/main/java` から Lombok のメンバーを生成し、
`target/delombok` のソースを `javac` でコンパイルします。編集は `src/main/java` で行います。
Maven の JVM では `sun.misc.Unsafe` によるメモリアクセスを拒否します。
Lombok は `.mvn/jvm.config` で開放したコンパイラのパッケージに、Java のリフレクションでアクセスします。
Picocli のコマンドや引数を変更した場合は、対応する GraalVM リフレクション設定も更新してください。
[本番用](src/main/resources/META-INF/native-image/dev.mikoto2000/rei-picocli/reflect-config.json)と
[テスト用](src/test/resources/META-INF/native-image/dev.mikoto2000/rei-picocli/reflect-config.json)に分かれています。

## テストと静的解析

| コマンド | 対象 |
| --- | --- |
| `./mvnw test -q` | Backend の日常用 Unit / 軽量 Component |
| `./mvnw verify -Pintegration` または `./mvnw test -Pfull` | Backend の回帰一式（SQLite、ファイルシステム、Spring Context、HTTP、実プロセスを含み、`live` を除外） |
| `./mvnw test -Pintegration-only` | Backend の `integration` / `e2e` のみ（`live` を除外） |
| `./mvnw -f terminal/pom.xml test` | 軽量 CLI の独立した reactor |
| `./mvnw -Pquality verify` | Backend の既定テストと Checkstyle・SpotBugs・Spotless の検査 |

実装変更を完了する前に対象の回帰一式を実行してください。外部サービスを使う `live-e2e` は明示的に実行します。[テスト分類と計測](docs/test-performance.md)・[実サービスを使うテスト](docs/live-environment-e2e.md)・[軽量 CLI の Windows E2E](docs/lightweight-cli-client.md#テストと実装状況)

CI の Java 全件回帰は Windows の2ジョブで実行します。Shard A は
[`.github/ci/java-shard-a.txt`](.github/ci/java-shard-a.txt) にあるクラスを実行し、
Shard B は Surefire の既定対象からそのクラスを除いた残りを実行します。
新規クラスは既定で Shard B に入り、Shard A のクラスを改名・削除する場合は一覧も更新します。
両ジョブの成功後、`test` ジョブがクラスの割り当て、テスト ID の重複、
および [想定スキップ](config/test-expected-skips.json) の ID と理由を照合します。

別の Java 静的解析ワークフローは Checkstyle と SpotBugs を実行し、両方の指摘が 0 件であることを確認します。レポートの欠落・形式不正や SpotBugs の解析エラーも失敗として扱います。[ローカル実行と CI レポート](docs/static-analysis.md)

## ローカルデータ

OS 標準の Rei Data Directory に保存します。Windows は `%LOCALAPPDATA%\Rei`、他 OS と移行方法は [設計メモ](docs/agent-run-project-state.md) を参照してください。`REI_DATA_DIR` で上書きできます。

- `<rei-data-dir>/storage.db`
  - Session・Turn・Project イベント・再送カーソル、会話受付と receipt などを管理する SQLite。起動時の Storage gate がデータディレクトリの排他ロックを取り、必要な移行を普通の Bean の初期化前に実行します。[ストレージ構成と移行](docs/storage-architecture.md)・[受付と排他](docs/lightweight-cli-client.md#session-admission-と冪等受付)
- `<rei-data-dir>/command-history/<project-id>.history`
  - プロジェクト別の JLine 入力履歴（各 1,000 件まで）。プロジェクトの UUID を使うため、同名プロジェクトも分離されます。
  - 次の入力受付前にプロジェクトの変更を確認し、変更前の履歴を保存して、変更先のファイルとメモリ上の履歴を切り替えます。終了時にも保存します。
  - `/project cd` 自体は切り替え前のプロジェクトに記録されます。Session の切り替えでは同じプロジェクトの入力履歴を使います。
  - 旧 `<rei-data-dir>/history` は残します。複数プロジェクトの入力が混ざっている可能性があるため、自動移行はしません。
- `<rei-data-dir>/memory.db`
  - 互換 ChatMemory などの SQLite
- `<rei-data-dir>/memory-consolidation.db`
  - 長期記憶・Sleep の履歴と要求、Work Context などの SQLite
- `<rei-data-dir>/vectorstore.db`
  - ベクトルストア専用 SQLite
- `<rei-data-dir>/extensions/sqlite-vec/...`
  - `sqlite-vec` のキャッシュ

軽量 CLI の入力履歴はクライアントのユーザーホーム配下の `.rei-cli/history` に保存します。[保存形式と無効化設定](docs/lightweight-cli-client.md#入力履歴と秘密)

## ベクトルストア実装

- ベクトルストアは `sqlite-vec` を使用します
- 保存先は `<rei-data-dir>/vectorstore.db` です
- 主実装は [`SqliteVectorStore.java`](./src/main/java/dev/mikoto2000/rei/vectorstore/SqliteVectorStore.java) です
- 文書一覧、削除、検索は `document_chunks_vec` の集約で処理します

現在の検索の要点:

- `vec0` 仮想テーブルで KNN 検索
- lexical prefilter
- 軽い rerank
- `source` / `docId` フィルタ
- adjacent chunk を考慮した snippet 生成

設定で dense と lexical の候補を別々に検索し、RRF で順位を統合する経路を有効化できます。lexical 候補には SQLite FTS5 の BM25 も選べます。[RRF の設定](docs/hybrid-rrf-retrieval.md)・[BM25 の設定](docs/document-bm25-retrieval.md)

## Web 検索実装

計測方法、品質回帰 fixture、現行経路と制約は
[Web 検索の改善記録](docs/web-search-optimization.md)を参照してください。

主なクラス:

- [`WebSearchService.java`](./src/main/java/dev/mikoto2000/rei/websearch/WebSearchService.java)
  - Web 検索プロバイダ呼び出し（`duckduckgo` / `brave` / `openai`）
- [`WebSearchQueryPlanner.java`](./src/main/java/dev/mikoto2000/rei/websearch/WebSearchQueryPlanner.java)
  - クエリ展開
- [`WebPageFetcher.java`](./src/main/java/dev/mikoto2000/rei/websearch/WebPageFetcher.java)
  - 上位 URL の HTML 取得
- [`WebPageExtractor.java`](./src/main/java/dev/mikoto2000/rei/websearch/WebPageExtractor.java)
  - HTML 本文抽出
- [`WebSearchAggregator.java`](./src/main/java/dev/mikoto2000/rei/websearch/WebSearchAggregator.java)
  - 一次情報 / 補足情報の分類と再ランキング
- [`SearchCommand.java`](./src/main/java/dev/mikoto2000/rei/core/command/SearchCommand.java)
  - ベクトル検索結果と Web 検索結果を束ねて prompt を組み立て

現在の挙動:

- 上位ページの本文取得
- クエリ展開
- 重複 URL 除外
- 一次情報優先の再ランキング
- Web 検索失敗時は VectorStore のみで継続

## URL 要約の文字コード

`/summarize` が使う `UrlContentFetchService` はレスポンスをバイト列で取得し、HTTP の `Content-Type` に有効な `charset` があれば優先します。指定がない、または未対応の場合は、HTML の先頭 4096 バイトを Jsoup で解析して `meta charset` または `meta http-equiv="Content-Type"` の文字コードを使います。どちらからも判定できなければ UTF-8 で読み込みます。

コメントやスクリプト内のタグ文字列は文字コード指定として扱いません。不正な文字コード指定は読み飛ばし、後続の有効な `meta` を探します。

## 文書埋め込み実装

主なクラス:

- [`EmbedCommand.java`](./src/main/java/dev/mikoto2000/rei/core/command/EmbedCommand.java)
- [`AsyncVectorDocumentService.java`](./src/main/java/dev/mikoto2000/rei/vectordocument/AsyncVectorDocumentService.java)
- [`VectorDocumentService.java`](./src/main/java/dev/mikoto2000/rei/vectordocument/VectorDocumentService.java)

現在の挙動:

- `embed add` は非同期
- `*`, `?`, `[]` を含む引数は Java 側で glob 展開
- 終了時に埋め込みが進行中なら警告を出して確認

## Run の停止と計測

会話 Run を一覧へ公開する前に停止対象を登録し、runner と所有する子処理の後片付けが完了してから終端を確定します。`POST /api/v1/runs/{runId}/cancel` の HTTP 202 は停止要求の受付を表し、完了は Run の状態照会または SSE で確認します。[停止のライフサイクル](docs/run-cancellation-lifecycle.md)

`AgentEventChatModel` は LLM イベントと時間計測を同じ Run ID に紐づけます。ストリーム購読を解除した場合、停止要求や割り込みなどのキャンセル状態があれば LLM Span を `CANCELLED`、通常の購読解除は `DISCONNECTED` として記録します。Span の状態と Run の終端状態はそれぞれのライフサイクルで確定します。[時間計測](docs/run-timing.md)・[キャンセル判定の回帰テスト](review/test-evidence.md#m-1-のキャンセル判定の回帰テスト)

## プロンプト組み立てとファイル読み取り

`ContextAssembler` は組み立てごとに Working Set を一度取得し、サイズの見積もりと最終プロンプトで共有します。ツール呼び出しと結果を組にした履歴の後ろに独立した `system` メッセージとして配置し、次のモデル呼び出しで取得し直します。[プロンプトとキャッシュ計測](docs/llm-prompt-cache.md)

`grepMultiQuery`、`searchAndRead`、`readMultiFile` は有効な項目の結果を返し、除外された項目には `PROJECT_PATH_EXCLUDED` と許可された Project 相対パスでの再試行案内を返します。ページ付き検索では、除外と一致結果を入力時のクエリ番号で参照できます。[変更と検証証跡](review/README.md)

## 終了開始イベント

グレースフルシャットダウンの開始時に `application.shutdown.started` を一度だけ発行します。
`/exit`・EOF による Shell 終了では購読解除前に、Spring コンテキスト終了では lifecycle の停止前に通知します。
payload は `{"reason":"shell_exit"}` または `{"reason":"context_closed"}` です。
プロセス全体のイベントなので `projectId`・`sessionId`・`turnId`・`runId` は `null` です。

Shell は「グレースフルシャットダウンを開始します。」を表示します。
Web API は接続中の `/api/v1/runs/{runId}/events` SSE に同じイベントを配信し、接続を完了します。
これは Run の成功・失敗・キャンセルを意味しません。Run 単位の replay には保存しません。
SSE bridge の破棄時には送信完了を最大 2 秒待機します。切断済み・送信不能な接続への配送や、強制終了時の通知は保証しません。

## MCP クライアントとサーバー

- Spring AI の MCP クライアントは、起動時に既定で `<rei-data-dir>/mcp-servers.json` から静的設定を読み込みます。[クライアントの設定](docs/configuration.md#mcp)
- 本人専用 MCP サーバーは明示的に有効化すると、同じ Backend の loopback 専用ポートに Streamable HTTP で 6 ツールを公開します。許可済みの Project に限定し、会話受付と実行には共通の Session・Run 基盤を使います。[サーバーの設定と契約](docs/mcp-server.md)・[安全境界](docs/mcp-server-security.md)

## AI ツール

チャット中の AI は、内部的に次のツール群を利用できます。

- ファイル操作、日付取得、外部コマンド実行
- Google Calendar の予定一覧・予定作成
- タスク作成・更新・完了・削除
- 日次ブリーフィング生成
- リマインド作成・一覧
- Web 検索
- MCP サーバー経由のツール

## テスト方針

実装時は小さく赤・緑・リファクタを回す前提です。最近の変更で主にカバーしている領域は次です。

- `EmbedCommandTest`
  - wildcard 展開
- `AsyncVectorDocumentServiceTest`
  - 進行中埋め込み件数
- `ReiApplicationExitConfirmationTest`
  - 終了確認
- `WebPageExtractorTest`
  - HTML 本文抽出
- `WebSearchQueryPlannerTest`
  - クエリ展開
- `WebSearchAggregatorTest`
  - 再ランキングと分類
- `SearchCommandTest`
  - Web + VectorStore の統合と fallback
- `SqliteVectorStoreTest`
  - `sqlite-vec` ベースの保存と検索

## 長期記憶と Sleep

プロジェクト固有の作業状態は [Work Context](docs/project-work-context.md) を参照してください。同じSQLite DataSourceに独立テーブルを追加し、Session所有権・Tool登録・Run終端・ContextAssemblerに統合します。Sleepの実行を前提としません。[設計とTDD記録](.kiro/specs/project-work-context/design.md)も参照してください。

`/sleep` で現在の Session の未処理 Turn を整理し、`/memory search` / `show` / `list` / `forget` で管理します。[長期記憶の設計と利用](docs/long-term-memory.md)

Auto Sleep は既定で無効です。`rei.memory.auto-sleep.enabled=true` の場合、アイドル時間・実行状況・未処理 Turn 数などを確認して一度に 1 バッチ処理します。Session 終了時とアプリの正常終了時に Sleep 要求を保存する設定も個別に有効化できます。保存した要求は再起動後にも引き継がれ、条件を満たすアイドル時に処理します。[記憶のライフサイクル](docs/memory-lifecycle-guide.md)・[終了時の Sleep 要求](docs/auto-sleep-end-requests.md)

## 補足

- [`application.yaml`](./src/main/resources/application.yaml) にはローカル差分が入りやすいので、コミット時は注意してください
- `<rei-data-dir>/` などの作業生成物は通常コミットしません

作業完遂の独立評価・10シナリオ・基準データの再現は [評価記録](docs/goal-completion-evaluation.md) を参照してください。

名前付き条件・受渡し・状態表示の互換性は [Goal Gate の拡張](docs/goal-completion-gate.md) を参照してください。

証拠に基づく進捗と Web 本文の重複排除は [停滞制御 Phase 2](docs/stagnation-aware-execution.md) を参照してください。
検証失敗の分類・修復予算・停止条件は [Goal 修復](docs/goal-verification-repair.md) を参照してください。
永続待機・条件再検証・Checkpoint 継続は [Goal の待機統合](docs/durable-goal-resume.md) を参照してください。
各 Phase の PR・評価指標・設定・制限は [実装記録](docs/goal-completion-implementation-report.md) を参照してください。

音声入力の隔離 PoC、調査・設計と未検証範囲は [Phase 0 調査](docs/voice-input-phase0.md) を参照してください。本番機能の進捗は各Phaseの実装報告を参照してください。

音声入力の共通入口と検証範囲は [Phase 1 実装報告](docs/voice-input-phase1.md) を参照してください。

音声入力のPhase 2実装・検証状況は [voice-input-phase2.md](docs/voice-input-phase2.md) を参照してください。

音声モデルの明示承認付き取得・オフライン再利用は [Phase 3](docs/voice-input-phase3.md) を参照してください。

音声入力の確認・訂正、Windows endpoint 監視、推論隔離と復旧操作は [Phase 4](docs/voice-input-phase4.md) を参照してください。

音声認識の現行モデル、承認付き取得、旧キャッシュと検証範囲は [Whisper large-v3-turbo FP32 移行](docs/voice-input-turbo-fp32.md) を参照してください。

音声認識の CPU 比較と設定選定・遅延の測定範囲は [Phase 5](docs/voice-input-phase5.md) を参照してください。

高度な音声機能の明示設定、呼びかけ・所有Runの停止・Windows内蔵TTS・再入力防止、およびGPU等の未対応範囲は [Phase 6](docs/voice-input-phase6.md) を参照してください。

Session単位の会話スタイル、音声限定設定、実行能力・承認を維持する仕様は [Phase 7](docs/voice-input-phase7.md) を参照してください。

音声入力・会話モードのPR一覧、利用手順、実測結果と未対応範囲は [実装記録](docs/voice-input-implementation-report.md) を参照してください。

Goal / Stage の責務、永続化、再開と操作は [Goal Stage実装](docs/goal-stage-implementation.md)、比較評価は [Goal Stage評価](docs/goal-stage-evaluation.md) を参照してください。
