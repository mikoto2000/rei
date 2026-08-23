# webSearchAndRead

## 目的

`webSearchAndRead` は公開 Web の検索と上位検索結果の本文取得を一つの Tool call で実行する。通常の Web 調査ではこの Tool を優先し、URL 候補だけが必要な場合は `webSearch`、既知の URL を読む場合は `fetchUrlContent` を使用する。既存の二つの primitive Tool はそのまま利用できる。

## 入力

```json
{
  "query": "Spring AI MCP OAuth support",
  "maxResults": 5,
  "readTop": 3
}
```

- `query`: 必須。空または空白のみはエラー。
- `maxResults`: 任意。既定は `rei.web-search.max-results`（標準設定は5）。`1..設定上限`。
- `readTop`: 任意。既定は `min(3, maxResults)`。`0..maxResults`。0なら本文取得を行わない。

## 出力

```json
{
  "query": "Spring AI MCP OAuth support",
  "results": [
    {
      "title": "Example",
      "url": "https://example.com/article",
      "snippet": "Search result snippet...",
      "publishedAt": "2026-08-01",
      "content": "Fetched and normalized page content...",
      "contentType": "text/html",
      "fetchStatus": "success",
      "errorType": null,
      "errorMessage": null,
      "truncated": false
    }
  ]
}
```

`fetchStatus` は `success`、`failed`、`not_requested` のいずれか。検索結果の順位は維持する。重複 URL は取得結果をリクエスト内で再利用するが、検索結果の各行自体は順位どおり返す。

## 再利用と安全性

検索は既存 `WebSearchService` を使用する。本文取得は既存 `UrlContentFetchService` を必ず通すため、`fetchUrlContent` と同じ URL validation、HTTP client、30秒 timeout、HTTP/network error handling を共有する。現在の共通 URL validation は http/https scheme のみに制限する。

取得本文は既存 `WebPageExtractor` で script、style、nav 等を除去し、空白を正規化する。1ページあたり2000文字まで返し、超過時は `truncated=true` とする。HTTP response の Content-Type は charset 部分を除いた MIME type として返す。取得・抽出失敗は該当結果だけ `failed` とし、検索全体は成功させる。検索自体の失敗だけは Tool 全体の例外として扱う。

Tool event は外側の `webSearchAndRead` call に対して一組だけ発行される。内部処理は Tool method を再呼び出さず service を共有するため、`webSearch` や `fetchUrlContent` の別 Tool call としては記録されない。debug log には検索結果数、実取得数、成功数、失敗数だけを出し、本文は出力しない。

## 実行方式

Phase 1 は逐次取得。標準上限が5件と小さく、検索順位の決定性、既存同期 API、リソース制御を優先した。クエリ分割、再ランキング、自動要約、高度な Readability、JavaScript 実行、認証ページ、永続キャッシュ、並列取得は対象外。
