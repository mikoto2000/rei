# 要件定義書: Search 機能

## はじめに

本機能は、AI エージェント「rei」にベクトルストア検索と Web 検索を統合した知識検索機能を提供する。
ユーザーはクエリを入力すると、ローカルのベクトルストアと外部 Web 検索の両方から情報を収集し、AI が統合して回答する。

## 用語集

- **SearchCommand**: ベクトルストアと Web 検索の結果を統合して AI 回答を生成するコマンド。
- **SearchKnowledgeService**: ベクトルストア検索と Web 検索を統合して結果を返すサービス。
- **SearchTools**: AI エージェントから知識検索を呼び出すためのツール。
- **VectorDocumentService**: ベクトルストアへの類似度検索を担うサービス。
- **WebSearchOrchestrator**: Web 検索クエリの計画・実行・ページ取得・集約を担うオーケストレーター。
- **WebSearchService**: DuckDuckGo または Brave Search API を使用して Web 検索を実行するサービス。
- **WebSearchQueryPlanner**: 入力クエリから複数の検索クエリを生成するコンポーネント。
- **WebSearchAggregator**: 取得した Web ページを一次情報と補足情報に分類するコンポーネント。
- **WebPageFetcher**: Web ページの本文を取得・抽出するコンポーネント。
- **WebSearchContext**: Web 検索結果を一次情報と補足情報に分類して保持するデータクラス。

## 要件

### 要件 1: 統合知識検索コマンド

**ユーザーストーリー:** ユーザーとして、クエリを入力してベクトルストアと Web の両方から情報を検索したい。そうすることで、ローカル文書と最新の Web 情報を統合した回答を得られる。

#### 受け入れ基準

1. WHEN ユーザーが `search <クエリ>` コマンドを実行したとき、THE SearchCommand SHALL ベクトルストア検索と Web 検索を実行する
2. THE SearchCommand SHALL 検索結果を AI に渡して日本語で統合回答を生成する
3. THE SearchCommand SHALL AI の回答をストリーミング形式でリアルタイムに表示する
4. THE SearchCommand SHALL 回答の後に参照ソース（ベクトルストアのソースと Web URL）を一覧表示する
5. WHEN `--vector-top-k` オプションが指定されたとき、THE SearchCommand SHALL ベクトル検索の返却件数を指定値に設定する
6. WHEN `--web-top-k` オプションが指定されたとき、THE SearchCommand SHALL Web 検索の返却件数を指定値に設定する
7. WHEN `--threshold` オプションが指定されたとき、THE SearchCommand SHALL ベクトル検索の類似度しきい値を指定値に設定する
8. WHEN `--source` オプションが指定されたとき、THE SearchCommand SHALL ベクトル検索を指定ソースに絞り込む

---

### 要件 2: Web 検索の実行

**ユーザーストーリー:** システムとして、DuckDuckGo または Brave Search API を使用して Web 検索を実行したい。そうすることで、最新の Web 情報を取得できる。

#### 受け入れ基準

1. WHEN Web 検索が有効のとき、THE WebSearchService SHALL 設定されたプロバイダーを使用して検索を実行する
2. THE WebSearchService SHALL DuckDuckGo と Brave Search API の両方をプロバイダーとしてサポートする
3. WHEN 複数のプロバイダーが設定されているとき、THE WebSearchService SHALL 各プロバイダーの結果を URL 重複排除しながら統合する
4. WHEN 一部プロバイダーの検索に失敗したとき、THE WebSearchService SHALL 他のプロバイダーの結果を返す
5. WHEN すべてのプロバイダーの検索に失敗したとき、THE WebSearchService SHALL 最初に発生したエラーを送出する
6. WHEN Web 検索が無効のとき、THE WebSearchService SHALL 無効である旨の例外を送出する
7. WHEN Brave Search API を使用するとき、THE WebSearchService SHALL API キーを Authorization ヘッダーに設定する
8. THE WebSearchService SHALL 検索結果の件数を `rei.web-search.max-results` プロパティの値を上限として制限する

---

### 要件 3: Web ページ本文の取得

**ユーザーストーリー:** システムとして、検索結果の URL から Web ページの本文を取得したい。そうすることで、スニペットだけでなく詳細な内容を AI の回答に活用できる。

#### 受け入れ基準

1. WHEN 検索結果の URL が取得できるとき、THE WebPageFetcher SHALL URL にアクセスしてページ本文を取得する
2. THE WebPageFetcher SHALL HTML からメインコンテンツのテキストを抽出する
3. WHEN ページ取得に失敗したとき、THE WebSearchOrchestrator SHALL 検索結果のスニペットをフォールバックとして使用する

---

### 要件 4: 検索クエリの計画

**ユーザーストーリー:** システムとして、入力クエリから複数の検索クエリを生成したい。そうすることで、より多くの関連情報を収集できる。

#### 受け入れ基準

1. THE WebSearchQueryPlanner SHALL 入力クエリから 1 件以上の検索クエリを生成する
2. THE WebSearchOrchestrator SHALL 計画された各クエリで Web 検索を実行して結果を統合する

---

### 要件 5: 検索結果の集約と分類

**ユーザーストーリー:** システムとして、取得した Web ページを一次情報と補足情報に分類したい。そうすることで、AI が情報の重要度を判断して回答を生成できる。

#### 受け入れ基準

1. THE WebSearchAggregator SHALL 取得した Web ページを一次情報と補足情報に分類する
2. THE SearchCommand SHALL AI プロンプトで一次情報を優先し、補足情報を補強として扱うよう指示する

---

### 要件 6: Web 検索スキップ時の通知

**ユーザーストーリー:** ユーザーとして、Web 検索がスキップされた場合にその理由を知りたい。そうすることで、設定の問題を認識して対処できる。

#### 受け入れ基準

1. WHEN Web 検索がスキップされたとき、THE SearchCommand SHALL スキップされた旨とその理由を表示する

---

### 要件 7: AI エージェントからの知識検索

**ユーザーストーリー:** AI エージェントとして、会話の中で必要に応じてベクトルストアと Web 検索を実行したい。そうすることで、最新情報や出典確認が必要な場合に適切な情報を提供できる。

#### 受け入れ基準

1. THE SearchTools SHALL AI エージェントからベクトルストアと Web 検索を統合して実行できるインターフェースを提供する
2. THE SearchTools SHALL 検索結果をベクトルストア結果・Web 一次情報・Web 補足情報に分けて返す

---

### 要件 8: 検索タイムアウトとキャンセル

**ユーザーストーリー:** システムとして、検索コマンドがタイムアウトまたはキャンセルされた場合に適切に処理したい。そうすることで、コマンドが無限に待機状態にならない。

#### 受け入れ基準

1. WHEN AI のストリーミング応答が 30 分以内に完了しないとき、THE SearchCommand SHALL ストリームを中断してタイムアウトメッセージを表示する
2. WHEN ユーザーが ESC キーを押したとき、THE SearchCommand SHALL 検索処理を中断してキャンセルメッセージを表示する
3. WHEN 検索中にエラーが発生したとき、THE SearchCommand SHALL エラーメッセージを表示する
