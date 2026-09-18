# コンテキスト自動圧縮

通常の Chat / Agent Run の LLM request 境界で、LLM に渡す Projection だけを縮小する。
永続 Conversation Log、ConversationTurnStore、Working Set は削除・置換しない。
既定で有効。`REI_CONTEXT_COMPRESSION_ENABLED=false` で従来の経路に戻せる。

## 調査結果と設計

- `ChatMemoryConfiguration` の最大100件は `MessageWindowChatMemory` の制限。JSONL の Conversation Log と JSON の ConversationTurnStore は別の永続履歴であり、この変更では保持件数を変更しない。
- 従来の `PromptChatMemoryAdvisor` は履歴を system prompt の文字列へ埋め込むため、圧縮対象の境界が分からない。CHAT では `ContextHistoryAdvisor` が完全な会話ログを sequence 付きの Message として読み出す。途中介入や補助コマンドの結果も含み、ログがない場合は永続 turn を使う。既存の bounded ChatMemory への入出力も維持する。
- `ContextBudgetManager` は未接続だった。request の入力予算と token 推定へ接続し、既存の section 優先順位の逆順ソートも修正した。
- `StagnationChatModel` が明示的な LLM → Tool → LLM ループを所有している。ここで毎回 Projection を作り直し、Tool 実行・イベント発行・進捗更新を終えてから次の request を作る。
- Working Set は従来 UserMessage に付加されていた。圧縮経路では独立した system section とし、LLM request ごとに最新状態を読み直す。Task State、Action Plan、ユーザーの追加指示などの非履歴 context も要約しない。
- `/memory summarize` は長期記憶の候補生成が目的。データモデルは統合せず、共通の `LlmModelProvider` を使用する。要約では memory / skill / tool advisors を通さない。
- Tool Event の本文は120文字程度の要約であり、原文の保存先として使えない。既存の project state 配下に小さな raw result store を追加した。

## 処理フロー

```text
Conversation Log / legacy Turns → ContextHistoryAdvisor → 通常の context advisors
                                                  ↓
User / 完了済み Tool Result → StagnationChatModel の request 境界
                                                  ↓
                  ContextAssembler（会話単位で直列化）
                     ├─ Working Set を別枠で更新
                     ├─ Tool Result 原文保存 / deterministic compact 化
                     ├─ 保存済み Summary + 未圧縮 Messages
                     └─ ContextBudgetManager で推定・判定
                                      ↓ threshold 超過
                            古い prefix の incremental 要約
                                      ↓
                       hard limit 再判定 / recent window 縮小
                                      ↓
                                     LLM
```

元の loop prompt は変更しない。Tool dispatcher には原文の prompt を渡し、モデルだけに Projection を渡す。
会話と実行中の Tool 往復は sequence の寿命が異なるため、会話 Summary と Run observations の二つの cursor を持つ。
次の会話では以前の Run observations も履歴データとして取り込み、会話要約へ統合できる。
これらはいずれも Working Set とは別である。

## 発動条件・設定

`rei.context-compression` で設定する。環境変数は `application.yaml` に定義している。

| 設定 | 既定値 | 意味 |
|---|---:|---|
| `enabled` | true | 自動圧縮の有効化 |
| `model-context-limit` | 128000 | モデルの context window |
| `completion-reserve` | 8192 | 出力予約。request の maxTokens が大きければそちらを使用 |
| `tool-reserve` | 4096 | Tool schema 予約。実際の schema 推定が大きければそちらを使用 |
| `safety-margin` | 4000 | 推定誤差への余裕 |
| `threshold` | 70000 | 要約を検討する入力 token 数 |
| `hard-limit` | 85000 | モデルに送信できる入力 token 数の上限 |
| `recent-tokens` | 12000 | 会話 / Tool 往復それぞれの最近の原文を残す予算 |
| `summary-tokens` | 3000 | 各 Summary の出力 token 上限 |
| `minimum-compression-gain` | 0.1 | 最低10%の削減を要求 |
| `tool-result-threshold` | 8000 | Tool result を短縮する推定 token 数 |
| `tool-result-tokens` | 2000 | 診断・head・tail 本文の予算（参照ヘッダーは別途計測） |
| `summary-timeout-seconds` | 60 | 要約 request の制限時間 |

実効 hard limit は `min(hard-limit, model-context-limit - completion - tools - safety)`。
threshold がその上限以上の場合は実効上限の直前に下げる。System Prompt 等は実際の Message として計測する。
モデル別に設定する場合:

```yaml
rei:
  context-compression:
    model-context-limits:
      "small-model": 32768
```

既存のモデル設定に context window の値はないため、モデル名から推測しない。
モデル / サーバーが提供する実際の window に合わせて設定する。fallback モデルがより小さい場合は、共通の安全な上限を設定する。

`TokenEstimator` は差し替え可能。既定は ASCII 約4文字/token、非ASCII 約2token/codepoint、message / tool-call の overhead と media 予約を加える概算。
正確な tokenizer ではない。最終 prompt を再計測し、保護対象だけで上限を超える場合は `CONTEXT_HARD_LIMIT` を返す。

## Summary・原文の保存

`<rei.data-dir>/projects/<projectId>/state/context/`（project なしでは `<rei.data-dir>/state/context/`）:

- `summaries/<scope UUID>.json`: summary、throughSequence、updatedAt。temp file と atomic replace で保存。
- `results/<conversation UUID>/<result UUID>.json`: toolName、toolCallId、完全な rawResult。

会話 cursor は JSONL の会話内 sequence を基準にする。新規ログには単調増加の sequence を追加し、再起動や時計の巻き戻りでも cursor を維持する。sequence がない既存ログは読み取り時に順序を割り当て、ファイルは書き換えない。
ログと legacy turn の cursor 名前空間を分離する。各履歴レコードに原文と Run observations 用のスロットを割り当てる。
Run cursor は同じ Run 内の request segment と message 位置。出力上限によるサブゴールへの分割でも sequence を再利用しない。
event stream の sequence とは独立している。既に summary に反映された範囲は再要約しない。

再起動時は Summary を読み直す。Run 自体の再開は既存同様に対象外。
ログも ConversationTurnStore もない従来の会話は bounded ChatMemory を互換入力として使う。既に失われた過去の memory を復元する処理は追加していない。
`/history` と既存の履歴検索は元の保存先を引き続き使う。

## Tool Result

小さい結果は同じ Message のまま。大きい結果では command / status / exitCode / failure / test summary 等を選び、head と tail、tool 名、call ID、rawResultRef を保持する。
JSON の stdout / stderr 内の改行も診断行の抽出に利用する。LLM による追加要約は行わない。
Tool 完了時に原文を保存し、returnDirect の結果も保存する。

AI は `readRawToolResult(rawResultRef, offset, limit)` で原文をページ取得できる。limit は1〜16000文字。
取得先は現在の conversation に限定する。巨大出力の取得自体も次の request で再度予算評価される。
本文を通常ログやイベントへ出力しない。raw store は既存履歴と同様にローカルの原文データである。

## キャンセル・並行性・失敗時

- 同じ conversation の read → 要約 → commit を interruptible な striped lock で直列化する。
- 要約は親の reactive subscription の interruptible worker で実行する。親の dispose により要約 stream も停止する。
- 保存前の cancellation check と commit は Run の monitor 内で行い、cancel 後の遅延保存を防止する。
- 要約 request も Run の LLM call budget を消費する。上限超過・キャンセルを通常の要約失敗として握りつぶさない。
- 各境界で会話・Runにつき最大1回の要約。要約入力もモデルの window に合わせて prefix を制限する。
- 失敗、空出力、出力上限、削減率不足、要約 timeout では既存 Summary を保持する。
- hard limit 超過時は Projection の古い会話・完結した Tool 往復を順に外す。Tool call/result の対を分断せず、最新の一往復を保持する。
- それでも収まらなければ明示エラー。System / 現在のユーザー入力 / Working Set を黙って削除しない。

## イベントと UI

`context.compression.started` / `.completed` / `.failed` を既存 Agent Event API に追加。
beforeEstimatedTokens、afterEstimatedTokens、compressedMessageCount、summaryThroughSequence、固定の reason を含む。
token 数は要約する prefix と以前の Summary の合計に対する値で、request 全体の推定値は debug log で確認できる。
owner / runId / timestamp / sequence は既存規約を使い、JSONL audit と SSE / Web DTO を通る。
要約本文・Tool 原文・例外の詳細は payload に含めない。

Java Projection と Native Client は unknown event を無視して cursor を進めるため、互換性を維持する。
今回は新しい UI パネルを追加していない。

## 検証・残課題

新規テストは budget 未到達、旧 prefix だけの要約、rolling summary、再起動、履歴保持、縮退、削減率不足、hard limit、Working Set 分離、Tool境界、raw結果保存、診断情報、event DTO、並行要求、実ストリームのキャンセル、ChatClient 統合を対象とする。
TDD の最初の失敗は estimator/policy 未実装、assembler 未実装、loop接続未実装、Web DTO の数値欠落として確認した。

残課題はモデル固有 tokenizer / media の精密な計測、raw result store の容量・保管期限ポリシー、圧縮状況専用 UI、大規模ログ読み取りの索引化。
保存済み原文を削除する cleanup はこの変更では実装しない。
