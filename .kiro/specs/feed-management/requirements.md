# 要件定義書: Feed 管理機能

## はじめに

本機能は、AI エージェント「rei」に RSS/Atom フィードの管理・取得・要約機能を提供する。
ユーザーは複数のフィードを登録・管理し、新着記事を収集して日次ブリーフィングに活用できる。
記事本文は保存せず、タイトル・URL・公開日時などのメタデータのみを扱う軽量設計とする。

## 用語集

- **Feed（フィード）**: RSS または Atom 形式で配信される記事一覧情報。
- **FeedItem（記事）**: フィードから取得した個々のエントリ。タイトル・URL・公開日時などのメタデータを持つ。
- **FeedService**: フィードおよび記事の CRUD 操作を担うサービス。
- **FeedFetcher**: HTTP 経由でフィードを取得し、RSS/Atom を解析するコンポーネント。
- **FeedUpdateService**: フィードの更新処理（取得・保存・失敗記録）を担うサービス。
- **FeedSummaryService**: AI を用いて記事またはブリーフィング全体を要約するサービス。
- **FeedTools**: AI エージェントからフィード操作を呼び出すためのツール群。
- **FeedUpdateJob**: 定期的にフィードを更新するスケジュールジョブ。
- **dedupe_key**: 記事の重複判定に使用するキー。URL 優先で生成する。
- **BriefingItem**: ブリーフィング表示用に整形された記事情報（ID・タイトル・URL・公開日時・フィード名）。

## 要件

### 要件 1: フィード登録

**ユーザーストーリー:** ユーザーとして、RSS/Atom フィードの URL を登録したい。そうすることで、新着記事を継続的に収集できる。

#### 受け入れ基準

1. WHEN ユーザーが有効な URL を指定してフィード追加コマンドを実行したとき、THE FeedService SHALL フィードをデータベースに保存する
2. WHEN 同一 URL のフィードが既に登録されているとき、THE FeedService SHALL 重複登録を拒否してエラーを返す
3. THE FeedService SHALL フィードに対して ID・URL・表示名・有効フラグ・作成日時・更新日時を保存する
4. WHERE 表示名が指定されたとき、THE FeedService SHALL 指定された表示名をフィードに設定する
5. WHEN フィードが登録されたとき、THE FeedService SHALL 有効フラグを有効状態で初期化する

---

### 要件 2: フィード一覧表示

**ユーザーストーリー:** ユーザーとして、登録済みフィードの一覧を確認したい。そうすることで、どのフィードが登録されているかを把握できる。

#### 受け入れ基準

1. WHEN ユーザーがフィード一覧コマンドを実行したとき、THE FeedService SHALL 登録済みフィードを ID 昇順で返す
2. THE FeedService SHALL 各フィードについて ID・URL・タイトル・表示名・最終取得日時・有効フラグを返す
3. WHEN 登録済みフィードが存在しないとき、THE FeedService SHALL 空のリストを返す

---

### 要件 3: フィード編集

**ユーザーストーリー:** ユーザーとして、登録済みフィードの表示名や有効/無効を変更したい。そうすることで、フィードの管理を柔軟に行える。

#### 受け入れ基準

1. WHEN ユーザーがフィード ID と新しい表示名を指定して編集コマンドを実行したとき、THE FeedService SHALL 表示名を更新する
2. WHEN ユーザーが有効フラグを指定して編集コマンドを実行したとき、THE FeedService SHALL 有効フラグを更新する
3. WHEN 編集対象のフィードが存在しないとき、THE FeedService SHALL エラーを返す
4. WHEN 表示名が指定されなかったとき、THE FeedService SHALL 既存の表示名を維持する

---

### 要件 4: フィード削除

**ユーザーストーリー:** ユーザーとして、不要になったフィードを削除したい。そうすることで、不要な記事収集を停止できる。

#### 受け入れ基準

1. WHEN ユーザーがフィード ID を指定して削除コマンドを実行したとき、THE FeedService SHALL 対象フィードをデータベースから削除する
2. WHEN フィードが削除されたとき、THE FeedService SHALL そのフィードに紐づく記事および取得失敗記録も合わせて削除する
3. WHEN 削除対象のフィードが存在しないとき、THE FeedService SHALL エラーを返す

---

### 要件 5: フィード取得・解析

**ユーザーストーリー:** システムとして、登録済みフィードを HTTP 経由で取得し、RSS/Atom 形式を解析したい。そうすることで、新着記事のメタデータを収集できる。

#### 受け入れ基準

1. WHEN フィード URL に対して HTTP GET リクエストを送信したとき、THE FeedFetcher SHALL レスポンスボディを取得する
2. WHEN HTTP レスポンスのステータスコードが 200 番台でないとき、THE FeedFetcher SHALL FeedFetchException を送出する
3. WHEN レスポンスボディが RSS 形式のとき、THE FeedFetcher SHALL RSS を解析してフィードタイトル・サイト URL・記事一覧を返す
4. WHEN レスポンスボディが Atom 形式のとき、THE FeedFetcher SHALL Atom を解析してフィードタイトル・サイト URL・記事一覧を返す
5. WHEN レスポンスボディが RSS でも Atom でもないとき、THE FeedFetcher SHALL FeedFetchException を送出する
6. WHEN 記事の公開日時が RFC-1123 形式のとき、THE FeedFetcher SHALL RFC-1123 形式として解析する
7. WHEN 記事の公開日時が ISO-8601 形式のとき、THE FeedFetcher SHALL ISO-8601 形式として解析する
8. IF 記事の公開日時が解析できない形式のとき、THEN THE FeedFetcher SHALL FeedFetchException を送出する

---

### 要件 6: フィード更新処理

**ユーザーストーリー:** ユーザーまたはシステムとして、フィードを更新して新着記事を保存したい。そうすることで、最新の記事情報を常に利用できる。

#### 受け入れ基準

1. WHEN ユーザーが単一フィードの更新コマンドを実行したとき、THE FeedUpdateService SHALL 対象フィードを取得して新着記事を保存する
2. WHEN ユーザーが全フィード更新コマンドを実行したとき、THE FeedUpdateService SHALL 有効状態のフィードをすべて更新する
3. WHEN フィードの取得に成功したとき、THE FeedUpdateService SHALL フィードのタイトル・サイト URL・説明・最終取得日時を更新する
4. WHEN フィードの取得に失敗したとき、THE FeedUpdateService SHALL 失敗日時・エラーメッセージ・HTTP ステータスを記録する
5. WHEN 一部フィードの取得に失敗したとき、THE FeedUpdateService SHALL 他のフィードの更新処理を継続する
6. THE FeedUpdateService SHALL 更新結果としてフィード名・追加件数・エラーメッセージを返す

---

### 要件 7: 記事重複排除

**ユーザーストーリー:** システムとして、同一記事の重複保存を防ぎたい。そうすることで、データベースに重複データが蓄積されない。

#### 受け入れ基準

1. WHEN 記事の URL が取得できるとき、THE FeedService SHALL URL を dedupe_key として使用する
2. WHEN 記事の URL が取得できないとき、THE FeedService SHALL タイトルと公開日時の組み合わせを dedupe_key として使用する
3. WHEN dedupe_key が既にデータベースに存在するとき、THE FeedService SHALL 記事を重複保存しない
4. WHEN dedupe_key が生成できない記事のとき、THE FeedService SHALL その記事を保存しない

---

### 要件 8: ブリーフィング用記事抽出

**ユーザーストーリー:** システムとして、指定期間内に公開された記事をブリーフィング用に抽出したい。そうすることで、日次ブリーフィングに新着記事を含められる。

#### 受け入れ基準

1. WHEN 開始日時・終了日時・最大件数が指定されたとき、THE FeedService SHALL 有効フィードの記事を公開日時降順で返す
2. THE FeedService SHALL フィードごとに最大件数を上限として記事を返す
3. THE FeedService SHALL 各記事について ID・タイトル・URL・公開日時・フィード名を返す
4. WHEN 対象期間の記事が存在しないとき、THE FeedService SHALL 空のリストを返す
5. THE FeedService SHALL フィード名として表示名・タイトル・URL の優先順で解決する

---

### 要件 9: 記事 AI 要約

**ユーザーストーリー:** ユーザーとして、特定の記事または新着記事全体を AI に要約させたい。そうすることで、記事の内容を効率的に把握できる。

#### 受け入れ基準

1. WHEN ユーザーが記事 ID を指定して要約コマンドを実行したとき、THE FeedSummaryService SHALL 対象記事の AI 要約を生成して返す
2. WHEN ユーザーがブリーフィング要約コマンドを実行したとき、THE FeedSummaryService SHALL 昨日 00:00 から現在時刻までの新着記事全体の AI 要約を生成して返す
3. WHEN 記事ページの取得に成功したとき、THE FeedSummaryService SHALL ページ本文を要約の入力として使用する
4. WHEN 記事ページの取得に失敗したとき、THE FeedSummaryService SHALL タイトルと URL のみを使用して要約を生成する
5. WHEN 対象期間の記事が存在しないとき、THE FeedSummaryService SHALL 記事が存在しない旨のメッセージを返す
6. THE FeedSummaryService SHALL AI が生成した要約テキストをそのまま返す

---

### 要件 10: 定期フィード更新

**ユーザーストーリー:** システムとして、設定されたスケジュールに従って自動的にフィードを更新したい。そうすることで、ユーザーが手動で更新しなくても新着記事が収集される。

#### 受け入れ基準

1. WHEN スケジュールされた時刻になったとき、THE FeedUpdateJob SHALL 全有効フィードの更新処理を実行する
2. THE FeedUpdateJob SHALL `rei.feed.cron` プロパティで指定された cron 式に従ってスケジュールを制御する
3. WHEN フィード更新中にエラーが発生したとき、THE FeedUpdateJob SHALL エラーをログに記録して処理を継続する

---

### 要件 11: AI エージェントからのフィード操作

**ユーザーストーリー:** AI エージェントとして、会話の中でフィードの操作を実行したい。そうすることで、ユーザーが自然言語でフィードを管理できる。

#### 受け入れ基準

1. THE FeedTools SHALL AI エージェントからフィード一覧取得・追加・削除・更新・要約の各操作を呼び出せるインターフェースを提供する
2. WHEN AI エージェントがフィード更新ツールを呼び出す際に ID が指定されないとき、THE FeedTools SHALL 全フィードを更新する
3. WHEN AI エージェントがフィード更新ツールを呼び出す際に ID が指定されたとき、THE FeedTools SHALL 指定フィードのみを更新する
