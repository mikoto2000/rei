# DEVELOP

デスクトップ監視の軽量観測・設定・限界・検証は [入力状態による軽量観測](docs/activity-lightweight-observation.md)を参照してください。
前面画像認識の頻度制御・時刻診断・性能比較は [画像認識キュー](docs/activity-vision-queue.md)を参照してください。

Rei の開発者向けドキュメントです。利用手順は [README.md](./README.md) を参照してください。

初めてコードを読む場合は、[図解付きプログラム構造ガイド](docs/program-structure.md)から始めてください。全体構成、会話の実行経路、状態と保存先、機能別の変更箇所を説明しています。

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

起動:

```bash
./mvnw spring-boot:run
```

テスト:

```bash
./mvnw test -q
```

日常用の Unit / 軽量 Component を実行します。SQLite、filesystem、Spring Context、HTTP、
実プロセスを含む全テストは `./mvnw verify -Pintegration`（テストのみなら
`./mvnw test -Pfull`）、Integration / System のみは `./mvnw test -Pintegration-only` です。
変更を完了する前に全テストを実行してください。分類、計測結果、再計測手順は
[テスト性能レポート](docs/test-performance.md) を参照してください。

## ローカルデータ

OS 標準の Rei Data Directory に保存します。Windows は `%LOCALAPPDATA%\Rei`、他 OS と移行方法は [設計メモ](docs/agent-run-project-state.md) を参照してください。`REI_DATA_DIR` で上書きできます。

- `<rei-data-dir>/history`
  - REPL 履歴
- `<rei-data-dir>/memory.db`
  - アプリ本体の SQLite
- `<rei-data-dir>/vectorstore.db`
  - ベクトルストア専用 SQLite
- `<rei-data-dir>/extensions/sqlite-vec/...`
  - `sqlite-vec` のキャッシュ

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

## Web 検索実装

計測方法、品質回帰 fixture、現行経路と制約は
[Web 検索の改善記録](docs/web-search-optimization.md)を参照してください。

主なクラス:

- [`WebSearchService.java`](./src/main/java/dev/mikoto2000/rei/websearch/WebSearchService.java)
  - Web 検索プロバイダ呼び出し (`duckduckgo` / `brave`)
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

## 終了開始イベント

グレースフルシャットダウンの開始時に `application.shutdown.started` を一度だけ発行します。
`/exit`・EOF による Shell 終了では購読解除前に、Spring コンテキスト終了では lifecycle の停止前に通知します。
payload は `{"reason":"shell_exit"}` または `{"reason":"context_closed"}` です。
プロセス全体のイベントなので `projectId`・`sessionId`・`turnId`・`runId` は `null` です。

Shell は「グレースフルシャットダウンを開始します。」を表示します。
Web API は接続中の `/api/v1/runs/{runId}/events` SSE に同じイベントを配信し、接続を完了します。
これは Run の成功・失敗・キャンセルを意味しません。Run 単位の replay には保存しません。
SSE bridge の破棄時には送信完了を最大 2 秒待機します。切断済み・送信不能な接続への配送や、強制終了時の通知は保証しません。

## MCP

- Spring AI MCP client を使用
- `<rei-data-dir>/mcp-servers.json` の静的設定を起動時に読み込み
- 動的登録やホットリロードは未実装

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

## 長期記憶 / Manual Sleep

プロジェクト固有の作業状態は [Work Context](docs/project-work-context.md) を参照してください。同じSQLite DataSourceに独立テーブルを追加し、Session所有権・Tool登録・Run終端・ContextAssemblerに統合します。Sleepの実行を前提としません。[設計とTDD記録](.kiro/specs/project-work-context/design.md)も参照してください。

`/sleep` で現在 Session の未処理 Turn を整理し、`/memory search` / `show` / `list` / `forget` で管理します。
設定、DB 互換性、出典追跡、Context Budget、失敗時の動作は [長期記憶の設計・利用ガイド](docs/long-term-memory.md) を参照してください。Auto Sleep はありません。

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
