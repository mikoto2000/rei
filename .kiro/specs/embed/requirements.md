# 要件定義書: Embed（ベクトルドキュメント管理）機能

## はじめに

本機能は、AI エージェント「rei」にドキュメントのベクトルストア管理機能を提供する。
ユーザーはローカルファイルをベクトルストアに登録・検索・削除でき、AI チャットや検索コマンドでの文脈付き回答に活用できる。
SQLite + sqlite-vec を使用したローカルベクトルストアにより、外部サービス不要で動作する。

## 用語集

- **EmbedCommand**: ベクトルストアへのドキュメント追加・検索・一覧・削除操作を提供するコマンド。
- **VectorDocumentService**: ドキュメントの読み込み・チャンク分割・ベクトル化・保存・検索・削除を担うサービス。
- **AsyncVectorDocumentService**: ドキュメントの追加処理を非同期で実行するサービス。
- **VectorDocumentRepository**: ベクトルストアへのドキュメント登録・削除・一覧取得を担うリポジトリ。
- **VectorStore**: ベクトルデータの保存と類似度検索を提供するストア（SqliteVectorStore）。
- **docId**: ドキュメントを一意に識別する UUID。
- **source**: ドキュメントのファイルパス（絶対パスに正規化）。
- **chunkIndex**: ドキュメントを分割したチャンクの順序インデックス。
- **OverlappingTokenTextSplitter**: オーバーラップ付きでテキストをチャンクに分割するスプリッター。
- **TikaDocumentReader**: Apache Tika を使用して PDF などのバイナリファイルからテキストを抽出するリーダー。

## 要件

### 要件 1: ドキュメントのベクトルストアへの追加

**ユーザーストーリー:** ユーザーとして、ローカルファイルをベクトルストアに登録したい。そうすることで、AI チャットや検索で文書の内容を参照できる。

#### 受け入れ基準

1. WHEN ユーザーが `embed add <ファイルパス...>` コマンドを実行したとき、THE EmbedCommand SHALL 指定されたファイルをベクトルストアへの追加キューに入れる
2. THE AsyncVectorDocumentService SHALL ドキュメントの追加処理を非同期で実行する
3. WHEN ドキュメントの追加が完了したとき、THE AsyncVectorDocumentService SHALL docId・ソース・チャンク数を標準出力に表示する
4. WHEN ドキュメントの追加に失敗したとき、THE AsyncVectorDocumentService SHALL エラーメッセージを標準出力に表示する
5. WHEN 同一ソースのドキュメントが既に登録されているとき、THE VectorDocumentService SHALL 既存のドキュメントを削除してから新しいドキュメントを登録する

---

### 要件 2: ワイルドカードによるファイル指定

**ユーザーストーリー:** ユーザーとして、ワイルドカードを使って複数ファイルを一括登録したい。そうすることで、ディレクトリ内のファイルをまとめて登録できる。

#### 受け入れ基準

1. WHEN ファイルパスにワイルドカード（`*`・`?`・`[`）が含まれるとき、THE EmbedCommand SHALL glob パターンとして展開して一致するファイルを列挙する
2. WHEN ワイルドカードに一致するファイルが存在しないとき、THE EmbedCommand SHALL エラーを返す
3. THE EmbedCommand SHALL 重複するファイルパスを除去して一意なリストを生成する

---

### 要件 3: ドキュメントのチャンク分割

**ユーザーストーリー:** システムとして、大きなドキュメントをチャンクに分割してベクトル化したい。そうすることで、長文ドキュメントでも精度の高い類似度検索が可能になる。

#### 受け入れ基準

1. THE VectorDocumentService SHALL ドキュメントをオーバーラップ付きトークンチャンクに分割する
2. WHEN ドキュメントが Markdown 形式のとき、THE VectorDocumentService SHALL セクション見出しを境界としてセクション単位に分割する
3. WHEN ドキュメントが PDF 形式のとき、THE VectorDocumentService SHALL Apache Tika でテキストを抽出してチャンク分割する
4. THE VectorDocumentService SHALL 各チャンクに docId・source・chunkIndex・ingestedAt のメタデータを付与する
5. WHEN ドキュメントが Markdown 形式のとき、THE VectorDocumentService SHALL チャンクサイズを通常の 75% に縮小する
6. WHEN ドキュメントが PDF 形式のとき、THE VectorDocumentService SHALL チャンクサイズを通常の 150% に拡大する

---

### 要件 4: ベクトルストアの類似度検索

**ユーザーストーリー:** ユーザーとして、クエリに関連するドキュメントをベクトルストアから検索したい。そうすることで、質問に関連する文書を素早く見つけられる。

#### 受け入れ基準

1. WHEN ユーザーが `embed search <クエリ>` コマンドを実行したとき、THE EmbedCommand SHALL ベクトルストアを検索して結果を表示する
2. THE VectorDocumentService SHALL 検索結果を docId 単位に集約して返す
3. THE VectorDocumentService SHALL 検索結果を複合スコア（ベクトル類似度 + 語彙カバレッジ）の降順で並べる
4. WHEN `--top-k` オプションが指定されたとき、THE EmbedCommand SHALL 指定件数を上限として結果を返す
5. WHEN `--threshold` オプションが指定されたとき、THE EmbedCommand SHALL 指定した類似度しきい値以上の結果のみを返す
6. WHEN `--source` オプションが指定されたとき、THE EmbedCommand SHALL 指定ソースのドキュメントのみを検索対象とする
7. THE EmbedCommand SHALL 各結果について docId・ソース・チャンクインデックス・スコア・スニペットを表示する
8. WHEN 検索結果が存在しないとき、THE EmbedCommand SHALL 一致する文書がない旨を表示する

---

### 要件 5: 登録済みドキュメントの一覧表示

**ユーザーストーリー:** ユーザーとして、ベクトルストアに登録されているドキュメントの一覧を確認したい。そうすることで、どのドキュメントが登録されているかを把握できる。

#### 受け入れ基準

1. WHEN ユーザーが `embed list` コマンドを実行したとき、THE EmbedCommand SHALL 登録済みドキュメントをソース単位でグループ化して表示する
2. THE EmbedCommand SHALL 各ソースについてドキュメント数・チャンク数・最終登録日時を表示する
3. WHEN 登録済みドキュメントが存在しないとき、THE EmbedCommand SHALL 登録済み文書がない旨を表示する

---

### 要件 6: ドキュメントの削除

**ユーザーストーリー:** ユーザーとして、不要になったドキュメントをベクトルストアから削除したい。そうすることで、古い情報が検索結果に影響しないようにできる。

#### 受け入れ基準

1. WHEN ユーザーが `embed delete --doc-id <docId>` コマンドを実行したとき、THE EmbedCommand SHALL 指定 docId のドキュメントをベクトルストアから削除する
2. WHEN ユーザーが `embed delete --source <source>` コマンドを実行したとき、THE EmbedCommand SHALL 指定ソースのすべてのドキュメントをベクトルストアから削除する
3. WHEN `--doc-id` と `--source` の両方が指定されたとき、THE EmbedCommand SHALL エラーを返す
4. WHEN `--doc-id` も `--source` も指定されなかったとき、THE EmbedCommand SHALL エラーを返す
5. WHEN 削除対象が存在しないとき、THE EmbedCommand SHALL 削除対象が見つからない旨を表示する

---

### 要件 7: SQLite ベクトルストアの初期化

**ユーザーストーリー:** システムとして、SQLite + sqlite-vec を使用したローカルベクトルストアを初期化したい。そうすることで、外部サービス不要でベクトル検索が動作する。

#### 受け入れ基準

1. THE SqliteVecInstaller SHALL アプリケーション起動時にプラットフォームに対応した sqlite-vec ネイティブライブラリを自動的にロードする
2. THE PlatformDetector SHALL OS（Linux・macOS・Windows）とアーキテクチャ（x86_64・aarch64）を検出して対応するライブラリを選択する
3. WHEN sqlite-vec ライブラリが見つからないとき、THE SqliteVecInstaller SHALL エラーを送出する
