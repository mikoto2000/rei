# Responses API の運用と受入判定

最新の接続先判定・有効化条件・設定例は [本番利用準備](responses-api-production-readiness.md) を参照。
Phase7〜9の実接続証拠は [実 API 受入試験](responses-api-live-validation.md) と各Phase記録を参照。
オフライン計画検査の成功は本番 gate の解除条件ではない。

## 本番利用の判定

Phase7〜9でOpenAIと指定ローカルの通信・推論再送・永続化・再開・圧縮・50操作を実APIで確認した。
指定ローカルの圧縮/動的公開/調査の結合は2試行FAILEDで、公式DeepSeek APIはUNVERIFIED。
既定の本番gateは閉じたまま。受入済みOpenAIの固定profileを明示した2用途だけが構成可能になる。
任意の推論品質、キャッシュ率、全LongのTTFTを認定したとは扱わない。
fixture factoryは所有済みloopback/`.invalid`の試験用であり、本番有効化には使わない。
本番プロセスの停止・再起動、JAR 配置、外部 application.yaml の書き換え、本番 DB migration は実施しない。

## 設定と API 切り替え

以下は汎用の受入試験向け概念例であり、profileなしでは本番gateを通過しない。
実装とbind試験を照合する有効化例は [OpenAI overlay](responses-openai-accepted.example.yaml)。
MODEL_ID は利用アカウントで受入確認する実際の ID に置き換える。提供元名・ホスト・モデル名から
API 形式や能力を推測しない。実効設定は Run 受付時に固定する。

```yaml
rei:
  llm:
    features:
      chat:
        api: responses
        base-url: ${RESPONSES_BASE_URL}
        api-key: ${RESPONSES_API_KEY}
        model: ${RESPONSES_MODEL_ID}
        max-output-tokens: 8192
        max-tools: 128
        endpoint-generation: 1
        reasoning-replay: encrypted
        supported-settings: []
```

OpenAI の例では [公式移行ガイド](https://developers.openai.com/api/docs/guides/migrate-to-responses) に沿い
base URL と model ID を受入環境で確認する。max-output-tokens は推論と表示本文を合わせた生成枠。
8192 は試験時に評価する設定例であり、全モデルの推奨値・利用可能枠を保証しない。
DeepSeek は [公式 Responses 仕様](https://api-docs.deepseek.com/guides/responses_api/) に沿って
独立した接続構成に `reasoning-replay: plaintext` を明示する。
encrypted_content / summary / verbosity / parallel_tool_calls 等を対応能力へ追加しない。
外部設定のreasoning-effort値の項目は提供しない。受入profileがlowへ固定する。架空の項目は追加しない。
SDK の型が存在することと、特定接続先・モデル・thinking mode がその設定を受理することは別である。

`max-tools` は接続先の要求上限。動的公開の枠は `rei.llm.tool-discovery.max-active-tools` で別に制限する。
exclusive mode は基本 31 ツールと searchTools を保持し、これらを収容できなければ送信前に拒否する。
`reasoning-replay: none` は別の明示構成であり、エラー時に自動切り替えしない。
structured output / 画像などは明示した supported-settings と使用経路の契約が必要になる。

feature の接頭辞は `rei.llm.features.<feature>.`。既存 feature 名を使い、独立接続先へ root の能力を暗黙継承しない。

| 項目 | 値と注意 |
| --- | --- |
| api | `chat-completions` / `responses`。未指定の既存設定は従来 API |
| accepted-responses-profile | 既定は未指定。現在は`openai-gpt-5.6-sol-low-20261125`だけ。完全な実効設定と用途も照合し、ローカル/未検証構成は拒否 |
| base-url / api-key / model | 接続先固有。キーは fingerprint・診断へ含めない |
| reasoning-replay | `encrypted` / `plaintext` / `none`。Responses は明示必須 |
| endpoint-generation | 1 以上。同じ接続先の所有者・再送互換性を変えた時だけ更新。キー更新だけで増やさない |
| max-output-tokens | 正の生成上限。Responses は max_output_tokens、CC の既存上限 family は維持 |
| max-tools | 正の接続先上限。未知は未指定。上限を超える公開 snapshot は送信前に拒否 |
| supported-settings | `temperature`, `top-p`, `reasoning-effort`, `reasoning-summary`, `text-verbosity`, `parallel-tool-calls`, `structured-output`, `image-input` の能力宣言。未指定は空。宣言と実機検証は別で、設定値そのものを生成しない |
| temperature | 明示した対応能力が必要。DeepSeek のモデル / thinking mode による制約も別途受入する |
| fallback-to-default | Responses から別 API へ自動継続しない。既存 CC の明示動作は維持 |

`rei.llm.read-timeout` は正のDuration、既定5分。受入native profileは共通値との小さい方で最大120秒、実効120秒の受入構成だけを許可する。
CCの共通timeoutを変更する必要はない。機能側の短い期限があれば短い方を適用し、
受付済み Run の snapshot を設定変更で書き換えない。
`rei.llm.max-stream-timeout-retries` は既存 Main の read timeout 許可回数（0〜3、既定2）。
増やしても総 LLM 回数・既知/不明 token の制約を上書きしない。
64 / 512 MiB など payload store の初期 quota は現在コード定義であり、架空の外部 YAML 項目で変更しない。

## 移行と切り戻し

1. Chat Completions の既存設定と、停止済み状態のデータバックアップを保存する。
2. 別のデータディレクトリと所有済みデータを使い、固定 SDK の契約 fixture と実接続受入を実施する。
3. API 形式、endpoint ID / 世代、model、再送方式、能力、生成上限、timeout を確定する。
4. 新規 Run だけを移行する。実行中・承認待ち・不明な副作用を持つ Run の接続先を変更しない。
5. 全条件・固定profile・設定例を確認し、利用者の本番適用承認後に有効化する。JAR版/API形式/DBの切戻しを別々に計画する。

切り戻しは新規 Run の API 設定を既存 Chat Completions に戻す。保存済み payload / receipt /
承認・Checkpoint を削除しない。旧コードが新 schema を更新できると推測しない。
途中の native Run は元の互換接続先で継続するか、既存の照合手順で手動判断する。
新しい model / API / endpoint 世代 / tools / System 文脈へ未完了 native Run を移すことはできない。
API キーだけの更新は同じ互換構成なら再送 binding を変えない。

専用 model-message schema は memory-consolidation.db 内の version 3。
既存 storage.db の version 22 / user_version と別で、専用 schema 作成・更新は原子的に行う。
本作業で本番 DB を開いて migration しない。未知の将来 version は拒否する。

## 保存、参照、容量不足

payload DB 自体の暗号化機能は本変更に含まれず、平文 reasoning とユーザー入力を含む。
既存の private data directory とバックアップと同じ機密管理対象として扱う。
正本は完全な ordered parts と型付き tool result。画面・読み上げ・検索・長期記憶には本文投影だけを渡す。
表示ログから推論を復元しない。所有者 / endpoint / hash / 公開参照の不一致は固定診断で停止する。
SDK 型の検証は Spring の集約ログより前に行い、不正 delta / item の本文・原因例外を流出させない。

| 制限 | 意味 |
| --- | --- |
| 8 MiB / メッセージ | JSON UTF-8 の実バイト上限。超過を切り詰めない |
| 64 MiB / Run | 新規保存した unique payload の累積。GC 後も使用量を戻さない |
| 512 MiB / 保存領域 | 全 scope の生存 payload bytes 合計。物理ファイルの上限ではない |
| staging 1 時間 | 公開処理途中のデータを GC から保護。期限切れの遅延公開は拒否 |

DB の空きページ、WAL、permanent receipts、Run 使用量、バックアップは payload 上限に含まれない。
空きディスクと DB / WAL サイズを既存の監視で確認し、書き込みの余裕を確保する。
SQLiteFull は失敗として返し、payload / receipt / 使用量をロールバックする。既存本文を切り詰めず、
保存失敗後にツールや次のモデル要求へ進まない。容量不足を再試行だけで解消しようとしない。

完了 Run の RUN 参照は、会話・継続・Checkpoint・after hook の耐久保存が成功した後に解放する。
承認 COMPLETED / DENIED は確定後にその APPROVAL 参照だけを解放する。外側 transaction の rollback
では保持する。UNKNOWN / EXPIRED / CANCELLED、未完了・不明な副作用は保守的に保持する。
要約の durable cursor が完了済み native turn 全体を覆った後、次回の履歴構築でその HISTORY 参照を
本文なしで検証・解放する。部分範囲、不明な anchor、欠損・改ざんしたログは保持する。
API / endpoint を変更し互換 native exchange がない場合も保持する。
他の履歴、承認、Checkpoint、実行中 Run の参照は解放しない。receipt は GC 後も冪等性の証拠になる。

複数 DB / JSONL の保存を一括 transaction と見なさない。外部公開の成否が不明な孤立 pin、旧 Run が
非稼働と証明できない Checkpoint lease は自動削除しない。参照先・操作台帳・実行状態を手動照合する。
余分な保持による容量圧迫は残存リスク。未知の副作用を自動再実行して容量を取り戻さない。
既存 `storage maintenance model-messages-gc` が期限切れ・無参照の本文だけを回収する。
独立 Scheduler は追加しない。物理ファイルの縮小やバックアップ削除をこのコマンドの効果と扱わない。

## 再試行、キャンセル、再開

SDK 内の自動再試行は 0。HTTP request body も one-shot とし、アプリの既存予算だけが再要求を許可する。
408 / 409 / 429 / 5xx は安全な分類だけを既存 bounded retry へ伝え、X-Should-Retry:false を尊重する。
Main の read timeout も既存の許可回数内だけで再要求する。token 使用量不明で上限を保証できない場合は停止。
401 / 403 / 不正入力は retry しない。ツール副作用の再実行を transport retry に含めない。
キャンセル・stream 切断・未完了終端では保存済み部分 item から callback を開始しない。

承認 / Checkpoint は、実 SQLite と別 JVM の再開 fixture で検証する。
保存済み完了結果は再配信し、残りの未実行 callback だけを進める。UNKNOWN は既存台帳で照合する。
Goal の最終完了はモデルの宣言だけで決めず、既存の成果物検証と永続予算を通す。
SubAgent は子自身の System / endpoint / 所有者・予算を使い、親の推論再送権限を引き継がない。

## SDK 更新時の検証

Spring AI 2.1.0 / OpenAI SDK 4.101.0 を基準とする。更新 PR では公開型の変更と mapper の挙動を確認し、
以下を固定 fixture で再実行する。SDK 内部クラスの反射や独自 SSE parser に置き換えない。

- 非 stream / SSE delta / item done / terminal、欠落・型不正・重複・不正 index・refusal・usage。
- original reasoning / phase / item ID / call_id / output の順序と immutable 正本。
- plaintext の wire content、自動 encrypted include の除去、未知属性・未対応設定の送信前拒否。
- Main / SubAgent / Goal、承認・別 JVM Checkpoint、接続先・tools・System 変更時の拒否。
- 動的公開 snapshot と LRU、120 ラウンド、繰り返し圧縮後の直前 tool output 保持。
- 保存・公開失敗、GC と writer の競合、SQLiteFull、quota、ハッシュ・所有者・future schema。
- 本文を含むログ・TTS・memory が増えないこと、architecture と全回帰・静的解析の基準維持。

長時間試験の heap 標本には fixture の request 記録も含まれる。プロファイラの peak live-set ではない。
実 API の cached_tokens と TTFT を fixture の値や wall time から推測しない。
性能値・最終 CI 件数と対応表は最終検証記録へ追記する。
