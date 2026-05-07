# 設計: 指定URL内容読み込みツール

## 概要
ユーザーが指定した `http/https` URL の内容を取得し、会話内で参照可能なテキストとして返却するツールを追加する。
入力検証・失敗理由の分類・既存機能への非影響を重視し、最小変更で導入する。

## スコープ
- 対象:
  - URL 入力の検証
  - URL 取得処理（HTTP GET）
  - レスポンス本文のテキスト化
  - 結果オブジェクト（成功/失敗、理由、メッセージ）の返却
- 非対象:
  - JavaScript 実行が必要な動的レンダリング
  - 認証付きサイト（Cookie/OAuth）対応
  - ファイルダウンロード用途のバイナリ処理

## アーキテクチャ

### 1. URLContentFetchTools（新規）
- 役割: AI ツールの公開エントリポイント
- I/F 例:
  - `UrlContentFetchResult fetchUrlContent(String url)`
- 処理:
  1. 入力 URL を受け取る
  2. `UrlContentFetchService` を呼び出す
  3. 成功/失敗をそのまま返却

### 2. UrlContentFetchService（新規）
- 役割: 取得処理とエラー分類の中核
- 処理:
  1. `UrlValidator` で URL を検証
  2. HTTP クライアントで GET 実行
  3. ステータスコード判定（2xx 以外は失敗）
  4. `Content-Type` を確認し、テキスト抽出対象を判定
  5. 本文を正規化して返却
- 依存:
  - `UrlValidator`（新規）
  - `HttpClient` / 既存 HTTP 基盤

### 3. UrlValidator（新規）
- 役割: 入力バリデーション
- ルール:
  - null/空文字を拒否
  - URI 構文妥当性を検証
  - スキームは `http` / `https` のみ許可

### 4. UrlContentFetchResult（新規DTO）
- フィールド案:
  - `boolean success`
  - `String content`（成功時）
  - `String errorType`（`INPUT_ERROR` / `HTTP_ERROR` / `NETWORK_ERROR` / `EXTRACTION_ERROR`）
  - `String errorMessage`
  - `Integer statusCode`（HTTP エラー時）

## フロー
1. Tool 呼び出し: `fetchUrlContent(url)`
2. Validator で入力検証
3. 不正なら `success=false, errorType=INPUT_ERROR`
4. HTTP GET 実行
5. 4xx/5xx なら `success=false, errorType=HTTP_ERROR, statusCode=...`
6. 通信例外/タイムアウトなら `success=false, errorType=NETWORK_ERROR`
7. 本文抽出失敗なら `success=false, errorType=EXTRACTION_ERROR`
8. 成功時は `success=true, content=...`

## エラーハンドリング設計
- 例外を直接外に投げず `UrlContentFetchResult` に正規化して返す
- ログ方針:
  - `debug`: URL（必要ならマスク）・HTTP ステータス・判定分岐
  - `warn`: 失敗時の概要（ネットワーク失敗、HTTP エラー等）
- 機密配慮:
  - クエリパラメータ全量はログに出さない方針を推奨

## 既存機能への影響
- 新規クラス追加中心で、既存会話・音声通知・定期実行フローは変更しない
- ツール登録箇所への追加のみ最小限で実施

## テスト設計

### 単体テスト
- `UrlValidatorTest`
  - null/空文字/不正形式/非 http(s) を拒否
  - 正常な http(s) を許可
- `UrlContentFetchServiceTest`
  - 2xx + テキスト本文で成功
  - 4xx/5xx で HTTP_ERROR
  - タイムアウト/IO 例外で NETWORK_ERROR
  - 抽出失敗で EXTRACTION_ERROR
- `UrlContentFetchToolsTest`
  - サービス結果を正しく透過返却

### 回帰テスト
- 既存テストスイート実行で非影響を確認

## 実装順序
1. `UrlContentFetchResult` 定義
2. `UrlValidator` 実装
3. `UrlContentFetchService` 実装
4. `URLContentFetchTools` 実装と登録
5. 単体テスト追加
6. 既存テスト実行
