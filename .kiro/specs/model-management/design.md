# 設計書: Model 管理機能

## 追補設計 (2026-05-08): モデル切替時のアンロード要求

### 目的
- `model <MODEL>` 実行でモデルを切り替える際、切替前のモデルに対してアンロード要求を送る。
- メモリ占有を抑え、不要モデルを明示的に解放する。

### 変更方針
- `ModelCommand` から `OpenAiCompatibleModelUnloadService` を呼び出す。
- 送信条件は「`currentModel` と `nextModel` が異なるとき」のみ。
- アンロード失敗時はモデル切替自体は継続し、`warn` ログを出力する（best effort）。

### コンポーネント
- **ModelCommand**
  - 変更前モデル名を取得し、変更有無を判定する。
  - 変更時のみアンロード要求を送信後、`ModelHolderService` を更新する。
- **OpenAiCompatibleModelUnloadService**
  - `spring.ai.openai.base-url` を基に `/api/generate` へ `POST` する。
  - `spring.ai.openai.api-key` が設定されていれば `Authorization: Bearer <apiKey>` を付与する。

### API 仕様
- Method: `POST`
- Endpoint: `{baseUrlNormalized}/api/generate`
  - `base-url` が `/v1` で終わる場合は `/v1` を除去して `/api/generate` を付加する。
  - それ以外はそのまま `/api/generate` を付加する。
- Request Body(JSON):
  - `model`: アンロード対象モデル名
  - `keep_alive`: `0`

```json
{
  "model": "qwen3.5:9b",
  "keep_alive": 0
}
```

### 例外・ログ
- HTTP ステータスが 2xx 以外: `IllegalStateException` を送出。
- ネットワークエラー/割り込み: `IllegalStateException` を送出（割り込み時は `Thread.currentThread().interrupt()` を復元）。
- `ModelCommand` 側ではこの例外を捕捉して `warn` ログ出力し、モデル切替処理は継続する。

### テスト観点
- `ModelCommand`
  - 異なるモデルへの切替時に `unload(currentModel)` が呼ばれる。
  - 同一モデル指定時は `unload` が呼ばれない。
- `OpenAiCompatibleModelUnloadService`
  - `base-url` が `/v1` なし/ありの双方で `.../api/generate` が正しく生成される。
