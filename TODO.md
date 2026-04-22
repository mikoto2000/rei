# RSS 管理機能 実装 TODO

## 1. ドメインモデルと永続化

- [ ] `Feed` エンティティを追加する
- [ ] `FeedItem` エンティティを追加する
- [ ] `FeedFetchFailure` エンティティを追加する
- [ ] `feeds` テーブルを追加する
- [ ] `feed_items` テーブルを追加する
- [ ] `feed_fetch_failures` テーブルを追加する
- [ ] `feed_items.url` を優先した重複防止制約を追加する
- [ ] フィード削除時に関連記事も削除する初期方針でスキーマとサービスを設計する

## 2. フィード管理サービス

- [ ] `FeedService` を追加する
- [ ] フィード登録を実装する
- [ ] フィード一覧取得を実装する
- [ ] フィード編集を実装する
- [ ] フィード削除を実装する
- [ ] 同一フィード URL の重複登録防止を実装する
- [ ] フィード登録時に取得できるタイトル、サイト URL、説明を保存する

## 3. フィード取得

- [ ] RSS/Atom 取得を行う `FeedFetcher` を追加する
- [ ] RSS と Atom の両形式を扱えるようにする
- [ ] 単一フィード更新処理を実装する
- [ ] 全フィード更新処理を実装する
- [ ] 記事メタデータとしてタイトル、URL、公開日時、取得日時を保存する
- [ ] URL なし記事向けの補助的な重複判定として `title + published_at` を実装する
- [ ] 取得失敗時にフィード ID、失敗日時、エラー内容、HTTP ステータスを記録する
- [ ] フィードごとの最終更新日時を更新する

## 4. フィード更新ジョブ

- [ ] 定期更新ジョブ `FeedUpdateJob` を追加する
- [ ] 手動更新用サービスメソッドを用意する
- [ ] 有効なフィードのみ更新対象にする
- [ ] 失敗したフィードがあっても全体処理を継続する
- [ ] 進捗と失敗内容を標準出力またはログへ出す

## 5. CLI コマンド

- [ ] `feed` コマンドを追加する
- [ ] `feed add` サブコマンドを追加する
- [ ] `feed list` サブコマンドを追加する
- [ ] `feed update` サブコマンドを追加する
- [ ] `feed edit` サブコマンドを追加する
- [ ] `feed delete` サブコマンドを追加する
- [ ] `feed summary` サブコマンドを追加する
- [ ] 記事単位要約用に `feed item summarize` 相当のコマンドを追加する
- [ ] ブリーフィング全体要約を手動実行するコマンドを追加する

## 6. ブリーフィング統合

- [ ] 昨日 00:00 から現在時刻までの記事抽出ロジックを実装する
- [ ] 対象を全有効フィードに限定する
- [ ] 記事タイトル、URL、公開日時、フィード名を返す DTO を追加する
- [ ] 公開日時の降順で並べる
- [ ] 0 件時に `昨日 00:00 以降の新着記事はありませんでした` を返す
- [ ] `BriefingService` に RSS 記事一覧を統合する
- [ ] `/briefing today` の表示に RSS セクションを追加する
- [ ] 将来の件数制御に備えて最大表示件数オプションを持てる構造にする

## 7. AI 要約

- [ ] 記事単位要約サービスを追加する
- [ ] 記事タイトル、URL、フィード名、公開日時を要約入力に使う
- [ ] 本文未保存前提の見出しベース要約プロンプトを追加する
- [ ] ブリーフィング全体要約サービスを追加する
- [ ] 今日の重要記事抽出、トピック別整理、全体傾向要約のプロンプトを追加する
- [ ] 要約失敗時のフォールバック応答を実装する

## 8. AI ツール連携

- [ ] AI ツールとして `feedList` を追加する
- [ ] AI ツールとして `feedAdd` を追加する
- [ ] AI ツールとして `feedUpdate` を追加する
- [ ] AI ツールとして `feedSummarizeItem` を追加する
- [ ] AI ツールとして `feedSummarizeBriefing` を追加する

## 9. 設定

- [ ] `FeedProperties` を追加する
- [ ] 定期更新間隔または cron を設定できるようにする
- [ ] ブリーフィングの最大表示件数を設定できるようにする
- [ ] 初期状態の有効/無効やタイムアウトを設定できるようにする
- [ ] `application.yaml` と `.rei/application.yaml` から設定できるようにする

## 10. テスト

- [ ] `FeedService` のユニットテストを追加する
- [ ] `FeedFetcher` のユニットテストを追加する
- [ ] 重複判定のユニットテストを追加する
- [ ] 取得失敗記録のユニットテストを追加する
- [ ] `FeedUpdateJob` のユニットテストを追加する
- [ ] `/briefing today` への RSS 統合テストを追加する
- [ ] `feed` コマンドの結合テストを追加する
- [ ] 記事単位要約とブリーフィング要約のテストを追加する

## 11. ドキュメント

- [ ] README に RSS 管理機能のセットアップ方法を追記する
- [ ] README に `feed` コマンドの使い方を追記する
- [ ] README に定期更新とブリーフィング統合の挙動を追記する

実装タスク:
- [ ] `book ask` サブコマンド追加
- [ ] 書籍限定の検索と回答生成を行う `BookQaService` 追加
- [ ] `bookId` で検索対象を絞り込む処理を追加
- [ ] 取得チャンクだけを根拠に回答するプロンプトを追加
- [ ] 根拠が不足する場合のフォールバック文言を追加
- [ ] 回答に参照チャンク番号を含める処理を追加
- [ ] AI ツールとして `bookAsk` を追加
- [ ] 書籍限定検索のサービス層テスト追加
- [ ] `book ask` の結合テスト追加

### 12. book summarize

目的:
取り込んだ本や教材の要点を短時間で把握できるようにする。

実装タスク:
- [ ] `book summarize` サブコマンド追加
- [ ] 書籍要約を生成する `BookSummaryService` 追加
- [ ] 対象書籍のチャンク一覧を取得する処理を追加
- [ ] 要点、重要キーワード、次に読むべき箇所を返すプロンプトを追加
- [ ] 長い書籍に備えて段階的要約へ拡張しやすい構成にする
- [ ] AI ツールとして `bookSummarize` を追加
- [ ] 書籍要約のサービス層テスト追加
- [ ] `book summarize` の結合テスト追加

## IT ニュース検索・要約

### 13. IT ニュース検索

目的:
AI やソフトウェア開発、クラウド、セキュリティなどの IT 系ニュースをまとめて収集できるようにする。

実装タスク:
- [ ] `news` コマンド追加
- [ ] `news it` サブコマンド追加
- [ ] IT ニュース検索条件を表す `ItNewsQuery` 追加
- [ ] Web 検索を使って IT ニュース候補を取得する `ItNewsService` 追加
- [ ] `topic` `limit` を指定できるようにする
- [ ] `since` または `days` を指定できるようにする
- [ ] `ai` `java` `security` `cloud` などの既定トピックを扱えるようにする
- [ ] タイトル、URL、要約、公開日時を返す `ItNewsItem` 追加
- [ ] ソースの重複 URL を除外する処理を追加
- [ ] 新着順で並べ替える処理を追加
- [ ] AI ツールとして `itNewsSearch` を追加
- [ ] IT ニュース検索のサービス層テスト追加
- [ ] `news it` の結合テスト追加

### 14. IT ニュース要約

目的:
取得した IT ニュースを短時間で把握できるようにし、何が重要かを先に理解できるようにする。

実装タスク:
- [ ] `news it --summarize` オプション追加
- [ ] IT ニュース要約を生成する `ItNewsBriefingService` 追加
- [ ] 取得したニュース一覧から重要トピックを抽出する処理を追加
- [ ] 類似ニュースをまとめる処理を追加
- [ ] 重要度順に並べる処理を追加
- [ ] 「何が起きたか」「誰に関係あるか」「実務影響」を返すプロンプトを追加
- [ ] 対象読者別の観点を返せるようにする
- [ ] 要約に元記事 URL 一覧を含める処理を追加
- [ ] AI ツールとして `itNewsBrief` を追加
- [ ] ニュース要約のサービス層テスト追加
- [ ] `news it --summarize` の結合テスト追加

### 15. 記事本文取得による高品質要約

目的:
検索結果のスニペットだけでなく記事本文をもとに要約し、精度を上げる。

実装タスク:
- [ ] URL から記事本文を取得する `WebFetchService` 追加
- [ ] `news fetch` サブコマンド追加
- [ ] 本文取得成功時のみ本文ベース要約を使う
- [ ] 失敗時は検索スニペット要約へフォールバックする
- [ ] 取得した本文から要点を抽出する処理を追加
- [ ] 対応しやすいニュースサイトから段階的に精度改善する
- [ ] 重複記事や要約済み記事のキャッシュ方針を追加
- [ ] AI ツールとして `webFetch` を追加
- [ ] 本文取得のサービス層テスト追加
- [ ] 本文ベース要約の結合テスト追加

## ベクトルストア操作の明示機能

### 13. vector document ops

目的:
文書をベクトルストアへ明示的に追加・検索・一覧・削除できるようにし、RAG 用データをユーザーが直接管理できるようにする。

実装タスク:
- [x] 現行の `embed` コマンドを整理し、`embed add` `embed search` `embed list` `embed delete` の各サブコマンド構成へ移行する
- [x] 既存の単発 `embed` 実行を `embed add` のエイリアスとして維持するか決め、互換方針を README に明記する
- [x] ベクトルストア操作をまとめる `VectorDocumentService` を追加する
- [x] 文書単位の識別子として `docId` を導入する
- [x] 埋め込み時に各 chunk の metadata へ `docId` `source` `chunkIndex` `ingestedAt` を保存する
- [x] `source` を正規化する処理を追加し、同一ファイルの再登録判定に使えるようにする
- [x] `embed add <files...>` で txt / markdown / pdf を読み込み、chunk 分割して保存する処理を `VectorDocumentService` へ移す
- [x] `embed add` 実行時に保存件数、chunk 数、対象ファイルを表示する
- [x] `embed add` の重複登録ポリシーを決める
- [x] 重複登録ポリシーに従い、同一 `source` の再登録時に上書き・スキップ・追加のいずれかを選べるようにする
- [x] ベクトルストア保存処理をサービス層へ寄せ、追加・削除後は必ず永続化する
- [x] `embed search <query>` を追加する
- [x] `embed search` で `topK` と `similarityThreshold` を指定できるようにする
- [x] `embed search` の結果に `docId` `source` `chunkIndex` `score` `snippet` を表示する
- [x] `embed search` で source 単位の絞り込みができるようにする
- [x] `embed list` を追加する
- [x] `embed list` で登録済み文書を `docId` `source` `chunk 数` `ingestedAt` 単位で集約表示する
- [ ] `embed list` で source の重複や欠損 metadata を検出できるようにする
- [x] `embed delete --doc-id <id>` を追加する
- [x] `embed delete --source <path>` を追加する
- [x] `embed delete` で対象 doc の全 chunk をまとめて削除する
- [ ] metadata が不足している既存データ向けに移行方針を決める
- [ ] 既存データ移行方針に従い、再取り込みを促すか、可能な範囲で旧データに暫定 `docId` を付与する処理を追加する
- [ ] `embed stats` または `embed doctor` を追加するか判断し、必要ならベクトルストア状態確認コマンドを追加する
- [x] AI ツールとして `vectorDocumentSearch` `vectorDocumentList` `vectorDocumentDelete` の追加要否を決める
- [ ] AI ツールを追加する場合は、副作用のある削除操作に承認が必要か整理する
- [x] README に `embed add/search/list/delete` の使い方を追記する
- [x] system prompt に「文書検索は vector store を優先するケース」を必要に応じて追記する
- [x] `VectorDocumentService` のユニットテストを追加する
- [x] metadata 付き登録処理のテストを追加する
- [x] source / docId 指定削除のテストを追加する
- [x] `embed search` の結合テストを追加する
- [x] `embed list` の結合テストを追加する
- [x] `embed delete` の結合テストを追加する

## ベクトルストアの SQLite 化

### 14. ベクトルストア抽象化

目的:
現在の `SimpleVectorStore` 直結実装を、SQLite バックエンドへ差し替えられる構造に整理する。

実装タスク:
- [x] `SimpleVectorStore` 依存箇所を洗い出し、移行対象一覧を固定する
- [x] `VectorStoreConfiguration` の戻り値型を `VectorStore` へ寄せる方針を固める
- [x] `AiConfiguration` の `QuestionAnswerAdvisor` 連携を `VectorStore` / `VectorStoreRetriever` 前提に変更する
- [x] `BriefingService` の依存を `SimpleVectorStore` から `VectorStoreRetriever` へ変更する
- [x] `VectorDocumentService` の依存を `SimpleVectorStore` から `VectorStore` へ変更する
- [x] `SimpleVectorStore.save(...)` 前提の保存処理を抽象化し、ストア固有処理を切り離す
- [x] `VectorStorePaths` の JSON ファイル前提 API を整理し、SQLite 用パス追加方針を決める
- [x] 既存ユニットテストを `VectorStore` 抽象前提へ更新する
- [x] 抽象化リファクタの回帰テストを追加する

### 15. SQLite ベクトルストア実装

目的:
SQLite に文書メタデータと埋め込みベクトルを永続化できる `VectorStore` 実装を追加する。

実装タスク:
- [x] SQLite ベースの `VectorStore` 実装クラスを追加する
- [x] 初期実装は `documents` と `document_chunks` の 2 テーブル構成にする
- [x] `documents` テーブルのスキーマを定義する
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
