# タスク: 指定URL内容読み込みツール（t_wada式TDD）

## 方針
- 進め方は **Red → Green → Refactor** を最小ステップで反復する。
- 1サイクルで実装する振る舞いは1つに限定する。
- 先にテスト名で意図を表現し、失敗理由を確認してから実装する。
- 通った後にのみリファクタリングを行い、都度テストを再実行する。

## 前提
- 対象 spec:
  - `F:\project\rei\.kiro\specs\url-content-fetch\requirements.md`
  - `F:\project\rei\.kiro\specs\url-content-fetch\design.md`
- 既存テストは壊さない。

## テスト戦略（TDDサイクル単位）

### サイクル 1: URL バリデーション（null/空）
1. Red
- `UrlValidatorTest` に `rejectsNullUrl` を追加
- `UrlValidatorTest` に `rejectsBlankUrl` を追加
- テスト実行して失敗を確認
2. Green
- `UrlValidator` を最小実装し、null/空を拒否
3. Refactor
- エラーメッセージ定数化など最小整理
- テスト再実行

### サイクル 2: URL バリデーション（スキーム/形式）
1. Red
- `rejectsNonHttpScheme`
- `rejectsMalformedUrl`
- `acceptsHttpAndHttps`
2. Green
- URI パース＋`http/https` 判定を最小実装
3. Refactor
- 判定ロジックをメソッド抽出
- テスト再実行

### サイクル 3: Result DTO の成功/失敗表現
1. Red
- `UrlContentFetchResultTest` に成功ケース/失敗ケース追加
2. Green
- `UrlContentFetchResult` を最小定義（`success`, `content`, `errorType`, `errorMessage`, `statusCode`）
3. Refactor
- factory メソッド（`success(...)`, `failure(...)`）導入
- テスト再実行

### サイクル 4: Service 成功系（2xx + text）
1. Red
- `UrlContentFetchServiceTest` に `returnsContentOn2xxTextResponse`
2. Green
- HTTP GET の最小実装で 2xx 成功時に `content` を返す
3. Refactor
- レスポンス判定を小メソッド化
- テスト再実行

### サイクル 5: Service HTTP エラー系（4xx/5xx）
1. Red
- `returnsHttpErrorOn4xx`
- `returnsHttpErrorOn5xx`
2. Green
- 2xx 以外を `HTTP_ERROR` として返却
3. Refactor
- ステータスコードマッピング整理
- テスト再実行

### サイクル 6: Service ネットワーク失敗系
1. Red
- `returnsNetworkErrorOnTimeout`
- `returnsNetworkErrorOnIoException`
2. Green
- 例外捕捉し `NETWORK_ERROR` を返却
3. Refactor
- 例外変換処理を集約
- テスト再実行

### サイクル 7: Service 抽出失敗系
1. Red
- `returnsExtractionErrorWhenBodyExtractionFails`
2. Green
- 抽出失敗時 `EXTRACTION_ERROR` を返却
3. Refactor
- 抽出ロジック分離
- テスト再実行

### サイクル 8: Tool 層の接続
1. Red
- `URLContentFetchToolsTest` に `delegatesToServiceAndReturnsResult`
2. Green
- `URLContentFetchTools` 実装（サービス呼び出しのみ）
3. Refactor
- 命名・依存注入整理
- テスト再実行

### サイクル 9: ツール登録と最小統合確認
1. Red
- 既存のツール一覧/統合テストに「登録されること」テストを追加
2. Green
- ツール登録箇所に `URLContentFetchTools` を追加
3. Refactor
- 登録コード整形
- テスト再実行

## 実装タスク一覧
1. `UrlContentFetchResult` を追加
2. `UrlValidator` を追加
3. `UrlContentFetchService` を追加
4. `URLContentFetchTools` を追加
5. DI/ツール登録コードを更新
6. 上記に対応する単体テストを追加

## 実行タスク一覧（各サイクル共通）
1. まず新規テスト1件だけ追加
2. 失敗テストを実行して失敗理由を確認
3. 最小実装で通す
4. 必要最小限のリファクタリング
5. 該当テスト群を再実行

## 完了条件
1. `requirements.md` の受け入れ条件を満たすテストが存在し、すべて成功する
2. 新規URL読み込み機能のテストが成功する
3. 既存主要テスト（少なくとも全体テスト）が成功する
4. 変更差分が新機能に限定され、既存挙動に不要な変更がない
