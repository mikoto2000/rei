# DEVELOP

Rei の開発者向けドキュメントです。利用手順は [README.md](./README.md) を参照してください。

## 開発環境

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

## ローカルデータ

起動したカレントディレクトリ配下の `.rei` にローカルデータを保存します。

- `.rei/history`
  - REPL 履歴
- `.rei/memory.db`
  - アプリ本体の SQLite
- `.rei/vectorstore.db`
  - ベクトルストア専用 SQLite
- `.rei/extensions/sqlite-vec/...`
  - `sqlite-vec` のキャッシュ

## ベクトルストア実装

- ベクトルストアは `sqlite-vec` を使用します
- 保存先は `.rei/vectorstore.db` です
- 主実装は [`SqliteVectorStore.java`](./src/main/java/dev/mikoto2000/rei/vectorstore/SqliteVectorStore.java) です
- 文書一覧、削除、検索は `document_chunks_vec` の集約で処理します

現在の検索の要点:

- `vec0` 仮想テーブルで KNN 検索
- lexical prefilter
- 軽い rerank
- `source` / `docId` フィルタ
- adjacent chunk を考慮した snippet 生成

## Web 検索実装

主なクラス:

- [`WebSearchService.java`](./src/main/java/dev/mikoto2000/rei/websearch/WebSearchService.java)
  - Brave Search API 呼び出し
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

## 文書埋め込み実装

主なクラス:

- [`EmbedCommand.java`](./src/main/java/dev/mikoto2000/rei/core/command/EmbedCommand.java)
- [`AsyncVectorDocumentService.java`](./src/main/java/dev/mikoto2000/rei/vectordocument/AsyncVectorDocumentService.java)
- [`VectorDocumentService.java`](./src/main/java/dev/mikoto2000/rei/vectordocument/VectorDocumentService.java)

現在の挙動:

- `embed add` は非同期
- `*`, `?`, `[]` を含む引数は Java 側で glob 展開
- 終了時に埋め込みが進行中なら警告を出して確認

## MCP

- Spring AI MCP client を使用
- `.rei/mcp-servers.json` の静的設定を起動時に読み込み
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

## 補足

- [`application.yaml`](./src/main/resources/application.yaml) にはローカル差分が入りやすいので、コミット時は注意してください
- `.rei/` などの作業生成物は通常コミットしません
