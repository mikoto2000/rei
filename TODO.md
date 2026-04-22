# RSS 管理機能 実装 TODO

## 1. ドメインモデルと永続化

- [x] `Feed` エンティティを追加する
- [x] `FeedItem` エンティティを追加する
- [x] `FeedFetchFailure` エンティティを追加する
- [x] `feeds` テーブルを追加する
- [x] `feed_items` テーブルを追加する
- [x] `feed_fetch_failures` テーブルを追加する
- [x] URL 優先の重複防止キーを追加する
- [x] フィード削除時に関連記事と失敗記録も削除する

## 2. フィード管理サービス

- [x] `FeedService` を追加する
- [x] フィード登録を実装する
- [x] フィード一覧取得を実装する
- [x] フィード編集を実装する
- [x] フィード削除を実装する
- [x] 同一フィード URL の重複登録防止を実装する
- [x] フィード取得後にタイトル、サイト URL、説明を保存する

## 3. フィード取得

- [x] RSS/Atom 取得を行う `FeedFetcher` を追加する
- [x] RSS と Atom の両形式を扱えるようにする
- [x] 単一フィード更新処理を実装する
- [x] 全フィード更新処理を実装する
- [x] 記事メタデータとしてタイトル、URL、公開日時、取得日時を保存する
- [x] URL なし記事向けの補助的な重複判定として `title + published_at` を実装する
- [x] 取得失敗時にフィード ID、失敗日時、エラー内容、HTTP ステータスを記録する
- [x] フィードごとの最終更新日時を更新する

## 4. フィード更新ジョブ

- [x] 定期更新ジョブ `FeedUpdateJob` を追加する
- [x] 手動更新用サービスメソッドを用意する
- [x] 有効なフィードのみ更新対象にする
- [x] 失敗したフィードがあっても全体処理を継続する
- [x] 更新結果を標準出力へ出す

## 5. CLI コマンド

- [x] `feed` コマンドを追加する
- [x] `feed add` サブコマンドを追加する
- [x] `feed list` サブコマンドを追加する
- [x] `feed update` サブコマンドを追加する
- [x] `feed edit` サブコマンドを追加する
- [x] `feed delete` サブコマンドを追加する
- [x] `feed summary` サブコマンドを追加する
- [x] `feed item summarize` サブコマンドを追加する
- [x] `feed item list` サブコマンドを追加する

## 6. ブリーフィング統合

- [x] 昨日 00:00 から現在時刻までの記事抽出ロジックを実装する
- [x] 対象を全有効フィードに限定する
- [x] 記事タイトル、URL、公開日時、フィード名を返す DTO を追加する
- [x] 公開日時の降順で並べる
- [x] 0 件時に `昨日 00:00 以降の新着記事はありませんでした` を返す
- [x] `BriefingService` に RSS 記事一覧を統合する
- [x] `/briefing today` の表示に RSS セクションを追加する
- [x] 最大表示件数オプションを `FeedProperties` で持てる構造にする

## 7. AI 要約

- [x] 記事単位要約サービスを追加する
- [x] 記事タイトル、URL、フィード名、公開日時を要約入力に使う
- [x] 本文未保存前提の見出しベース要約プロンプトを追加する
- [x] ブリーフィング全体要約サービスを追加する
- [x] 今日の主要トピックと重要記事を返すブリーフィング要約プロンプトを追加する
- [x] 要約失敗時のフォールバック応答を実装する

## 8. AI ツール連携

- [x] AI ツールとして `feedList` を追加する
- [x] AI ツールとして `feedAdd` を追加する
- [x] AI ツールとして `feedUpdate` を追加する
- [x] AI ツールとして `feedSummarizeItem` を追加する
- [x] AI ツールとして `feedSummarizeBriefing` を追加する

## 9. 設定

- [x] `FeedProperties` を追加する
- [x] 定期更新間隔を設定できるようにする
- [x] ブリーフィングの最大表示件数を設定できるようにする
- [ ] 初期状態の有効/無効やタイムアウトを設定できるようにする
- [x] `application.yaml` と `.rei/application.yaml` から設定できるようにする

## 10. テスト

- [x] `FeedService` のユニットテストを追加する
- [x] `FeedFetcher` のユニットテストを追加する
- [x] 重複判定のユニットテストを追加する
- [x] 取得失敗記録のユニットテストを追加する
- [x] `FeedUpdateJob` のユニットテストを追加する
- [x] `/briefing today` への RSS 統合テストを追加する
- [x] `feed` コマンドの結合テストを追加する
- [x] 記事単位要約とブリーフィング要約のテストを追加する

## 11. ドキュメント

- [x] README に RSS 管理機能のセットアップ方法を追記する
- [x] README に `feed` コマンドの使い方を追記する
- [x] README に定期更新とブリーフィング統合の挙動を追記する
- [x] `document_chunks` テーブルのスキーマを定義する
- [x] 検索結果の返却単位を chunk 単位にすることを明記する
- [x] `documents.doc_id` と `document_chunks.chunk_id` の一意制約を定義する
- [x] `documents` / `document_chunks` の外部キー制約と cascade delete 方針を定義する
- [x] `docId` `source` `chunkId` 向け index を追加する
- [x] 文書本文、metadata、embedding を保存する insert 処理を実装する
- [x] chunk ID 指定で削除する delete 処理を実装する
- [x] `docId` 指定で削除する delete 処理を実装する
- [x] `source` 指定で削除する delete 処理を実装する
- [x] 再登録時の置換方針を `source` 単位の delete + insert で統一する
- [x] `SearchRequest` を受けて候補 chunk を取得する read 処理を実装する
- [x] `topK` と `similarityThreshold` を適用する処理を追加する
- [x] metadata による `source` / `docId` 絞り込みを適用する処理を追加する
- [x] 類似度指標を cosine similarity に固定する
- [x] ベクトル正規化の実施タイミングを決める
- [x] embedding 次元不一致時のエラー方針を決める
- [x] 類似度計算を Java 側で行う暫定実装を追加する
- [x] 埋め込みベクトルの保存形式を JSON か BLOB かで決めて統一する
- [x] 埋め込みベクトル保存形式の判断基準として、実装容易性、読み出し性能、サイズ効率、デバッグ容易性を明記する
- [x] 文書登録を 1 文書 1 トランザクションで実行する
- [x] 文書削除を関連行込みで 1 トランザクションで実行する
- [x] 部分失敗時のロールバック方針を定義する
- [x] SQLite 初期化処理を `DataSourceConfiguration` と整合する形で追加する
- [x] SQLite ファイル破損時の扱いを定義する
- [x] SQLite ロック発生時の扱いを定義する
- [x] metadata 欠損データの扱いを定義する
- [x] 存在しない chunk / doc 削除時の扱いを定義する
- [x] スキーマ初期化のユニットテストを追加する
- [x] 複数文書・複数 chunk 登録テストを追加する
- [x] `docId` / `source` 絞り込み検索テストを追加する
- [x] `topK` と `similarityThreshold` の境界値テストを追加する
- [x] cascade delete / 関連削除テストを追加する
- [x] 再登録時の置換テストを追加する
- [x] 空ストア検索テストを追加する
- [x] embedding 次元不一致テストを追加する

### 16. 既存ベクトル文書機能の載せ替え

目的:
既存の文書埋め込み・検索・削除機能を SQLite バックエンド前提へ載せ替える。

実装タスク:
- [x] `VectorDocumentService` の保存先を SQLite ベクトルストアへ切り替える
- [x] `docId` / `source` / `chunkIndex` / `ingestedAt` を SQLite 側で保持するようにする
- [x] `embed add` の再登録時に source 単位で置換できることを確認する
- [x] `embed search` の結果整形が載せ替え後も維持されるようにする
- [x] `embed list` の source 集約表示が載せ替え後も維持されるようにする
- [x] `embed delete --doc-id` が載せ替え後も維持されるようにする
- [x] `embed delete --source` が載せ替え後も維持されるようにする
- [x] AI ツール `vectorDocumentAdd` `vectorDocumentSearch` `vectorDocumentList` `vectorDocumentDeleteByDocId` `vectorDocumentDeleteBySource` の回帰テストを追加する
- [x] `QuestionAnswerAdvisor` の検索結果が載せ替え後も使えることを確認する
- [x] `dailyBriefing` の関連文書取得が載せ替え後も使えることを確認する
- [x] `SqliteVectorStore` の検索で全件走査を避けるため、lexical prefilter か候補件数絞り込みを追加する
- [x] `VectorDocumentService` の doc 単位集約を `max score` のみから複合スコア方式へ改善する
- [x] snippet 生成を query 近傍切り出しから「最も一致する文」優先へ改善する
- [x] 同一文書の近接 `chunkIndex` をまとめて返す前後チャンク統合ロジックを追加する
- [x] ベクトル類似度に語句一致を混ぜるハイブリッド検索を検討・実装する
- [x] タイトル、見出し、ファイル種別、タグなど検索改善に使うメタデータ拡張を追加する
- [x] 文書種別ごとに chunk size / overlap / splitter 戦略を切り替えられるようにする
- [x] 上位候補の再ランキング層を追加するか判断し、必要なら rule-based または LLM rerank を実装する

### 17. 旧 JSON 永続化コードの撤去

目的:
未リリースのうちに JSON ベース永続化を整理し、SQLite 前提の実装へ単純化する。

実装タスク:
- [x] `vector-store.json` 前提の保存・読込コードを削除する
- [x] `vector-documents.json` 前提の保存・読込コードを削除する
- [x] `VectorStorePaths` の JSON 用パス API を削除または SQLite 用へ置き換える
- [x] README から JSON ベクトルストア前提の説明を削除する
- [x] 旧 JSON 永続化コード削除後の回帰テストを追加する

### 18. 設定・運用・ドキュメント整備

目的:
SQLite ベクトルストアを安全に運用できる設定と説明を揃える。

実装タスク:
- [ ] ベクトルストア種別を切り替える `rei.vector-store.type` 設定を追加する
- [ ] SQLite ベクトル DB ファイルの保存先設定を追加する
- [ ] 開発時は `simple` / 本番は `sqlite` のような切り替え方針を決める
- [ ] 起動時ログに利用中ベクトルストア種別を出すようにする
- [x] README に SQLite ベクトルストアの構成と制約を追記する
- [x] 類似度計算を Java 側で行う場合の性能制約を README に明記する
- [ ] `score > 0` を返却条件とする現行仕様を README またはコードコメントに明記する
- [ ] 将来的に pgvector 等へ差し替えやすい設計方針を TODO または README に追記する
- [ ] Native Image 影響の有無を確認する
- [ ] 起動確認と主要コマンドの手動確認手順を追記する
