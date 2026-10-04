# Long-term Memory / Sleep

長期記憶は、別 Session でも役立つ情報を会話から抽出した知識です。Conversation History が原典、Working Set が現在の作業対象、Rolling Summary が会話継続のための圧縮情報です。Sleep はこれらを削除・更新しません。

## コマンド

```text
/sleep
/sleep preview
/sleep status
/sleep history
/memory search computer use
/memory show mem_<id>
/memory list --limit 20 --offset 0
/memory forget mem_<id>
```

現在選択中の Session と Project を使用します。`/sleep` は確定した未処理 Turn を一括処理し、件数と主要変更を表示します。1 回の上限を超える場合は、再度 `/sleep` を実行してください。完了・失敗・キャンセルした Turn の追記位置を使用し、実行中 Turn の手前で止まります。失敗・キャンセルした Turn は処理位置の対象には含めますが、確定知識の抽出対象からは除外します。

`preview` は同じ抽出・照合を行い、候補の scope/type/confidence/importance、予定 action、対象の既存記憶を表示します。記憶、アクセス日時、実行履歴、処理位置を一切更新しません。LLM 呼び出しと lifecycle event の発行は行います。

`status` は GLOBAL と現在 Project の ACTIVE / SUPERSEDED / ARCHIVED の件数、現在 Project の最終実行、選択 Session の未処理 Turn 数を表示します。`history` は現在 Project の直近 100 実行を表示します。時刻、Session、Project、処理範囲・件数、各 action 件数、失敗件数を含みます。

`list` と `search` は GLOBAL + 現在 Project の ACTIVE のみ。`show` は同範囲の過去状態も表示し、出典 Session/Turn、関連、更新日時、有効期間を確認できます。`forget` は ARCHIVED に変更し、物理削除しません。検索文字列は FTS 構文として解釈せず、通常の語句として扱います。

## 型・scope・状態

| 型 | 用途 |
|---|---|
| FACT | 安定した事実 |
| PREFERENCE | 明示された恒常的な好み |
| DECISION | 決定された設計・運用方針 |
| CONSTRAINT | 守るべき制約 |
| PROJECT_STATE | 確定した Project の状態 |
| PROCEDURE | 再利用できる手順 |
| LESSON | 実際の試行・結果から得た知見 |
| RELATION | エンティティの関係 |

GLOBAL は Project 非依存、PROJECT は既存 ProjectRegistry の Project ID に所属します。Scope を勝手に移動せず、照合・変更対象は同一 scope と同一 Project に限定します。通常は ACTIVE、置換されたものは SUPERSEDED、明示的に忘れるものは ARCHIVED です。

## 抽出・Reflection・統合

既存 `LlmFeature.MEMORY` のモデル設定を再利用し、ツール・会話履歴 Advisor を付けない専用リクエストで抽出します。LLM 出力は SubAgent と共通の strict JSON parser と Draft 2020-12 schema loader/validator で検証します。コードフェンス、余分な値、重複キー、未知の型、範囲外スコア、未知の出典 Turn は保存しません。

挨拶、雑談、その場の感情、単発質問、途中状態、根拠のない推測、未採用の LLM 提案は除外するよう指示します。LESSON / PROCEDURE は会話で確認できた失敗・改善・手順から抽出し、一般論を生成させません。confidence は根拠の確かさ、importance は再利用価値です。しきい値未満は Java 側で IGNORE にします。

| Action | 保存動作 |
|---|---|
| NEW | 新規 ACTIVE 記憶 |
| DUPLICATE | 既存の出典・タグを追加、件数を増やさない |
| UPDATE | 同じ ID の本文等を更新、元の出典・作成日時を保持 |
| MERGE | 統合記憶を作成し、元記憶を SUPERSEDED、元出典と relation を保持 |
| SUPERSEDE | 新方針を作成し、旧記憶を SUPERSEDED、supersededBy と validUntil を設定 |
| CONFLICT | 双方を残して CONFLICT relation を作成、レポートへ表示 |
| IGNORE | 保存しない |

完全一致は Java の正規化キーで判定します。検索候補数とは独立に完全一致を取得し、ACTIVE の scope / Project / 正規化本文に一意制約を設けて、同時登録による重複も防ぎます。意味的照合は検索候補 + 直近の記憶を入力上限内で LLM に渡し、action と対象 ID を schema 検証します。対象 ID、scope、状態、対象数は Java で再検証します。1 batch 内で同じ既存記憶に複数の変更が提案された場合は、曖昧な順序で上書きせず全体を失敗させます。`max-turns` を減らして再実行できます。

## Retrieval と Context Budget

SQLite FTS5 trigram で content / summary / tags を検索します。日本語にも部分一致でき、3 文字未満の語句にはエスケープした LIKE を使います。外部 Vector DB や embedding 呼び出しは追加していません。

語句一致度 0.50、Project 一致 0.10、importance 0.20、confidence 0.15、更新の新しさ 0.05 を加算して順位付けします。GLOBAL + 現在 Project の ACTIVE、有効期間内のみが対象です。未解決 CONFLICT に関係する記憶は、自動注入を保守的に除外します。別 Project の記憶は投入しません。

`MemoryContextAdvisor` は通常 CHAT にのみ登録され、履歴組み立て後の補助 System Message として注入します。ユーザー入力・履歴・Working Set・Rolling Summary を変更しません。`TokenEstimator.conservative()` でヘッダーと message overhead を含めて上限を確認し、既存 ContextAssembler の最終予算計算にも含めます。圧縮後も hard limit を超える場合、追加の履歴を削る前に補助記憶を外します。長すぎる個別記憶は切り詰めずスキップします。検索障害は記憶なしで Chat を継続します。

## 設定

既存の `rei.memory.enabled` と `rei.llm.features.memory` を使用します。

```yaml
rei:
  memory:
    enabled: true
    retrieval:
      max-memories: 5
      max-tokens: 1500
    sleep:
      min-confidence: 0.70
      min-importance: 0.50
      max-turns: 50
      max-input-tokens: 12000
      timeout-seconds: 120
```

`memory.enabled=false` は新しい Sleep・memory list/search/show/forget と自動 Retrieval を無効化します。旧 export/summarize/consolidate は互換コマンドとして残っています。旧 `auto-trigger-*` 設定は従来の提案通知専用であり Sleep を起動しません。

## DB と互換性

既存の `memoryConsolidationDataSource` / `ReiPaths.memoryConsolidationDbPath()` を利用します。保存先は `<rei-data-dir>/memory-consolidation.db`。Data Directory は既存の OS 既定値または `REI_DATA_DIR` に従います。

既存 `memories` に project_id / summary / importance / last_accessed_at / valid_from / superseded_by / content_key を追加し、`memory_sources` に session_id / turn_id を追加します。既存 memory_tags / memory_relations と新規 sleep_runs / long_term_memory_fts を利用します。旧 memory_fts も新規・更新時に同期します。

旧 Memory record と旧 enum 値は互換性のため保持します。長期記憶用の `LongTermMemory` は同じ memories テーブルの拡張ビューです。Project と出典が不明な旧行を GLOBAL に推測移行せず、通常 Retrieval と新コマンドでは除外します。旧行は既存 export 等で引き続き参照できます。

## 失敗・再実行・キャンセル

抽出と照合を完了してから、記憶・source/tag/relation/FTS・成功 Sleep Run を単一 SQLite トランザクションで書き込みます。成功 Run の最大 toSequence が処理位置です。schema failure、timeout、キャンセル、DB failure では処理位置を進めません。失敗・キャンセル Run は別の監査書き込みとして記録し、DB 自体が書き込めない場合は元の例外を保持します。

同一 Session の同時実行をプロセス内で拒否します。他 Session が同じ記憶を変更した場合も、書き込み前に snapshot を比較して中断します。CLI は既存 ESC cancellation に接続し、LLM stream の待機を interrupt でキャンセルします。適用中もキャンセルをチェックして rollback します。

イベントは `memory.sleep.started` / `memory.sleep.completed` / `memory.sleep.failed` / `memory.retrieval.completed`。記憶本文を送らず、所有 Project / Session と件数を送ります。CLI は開始と終了のレポートも表示します。

## Auto Sleep

`rei.memory.auto-sleep.enabled=true` で有効化します。既定では無効です。

```yaml
rei:
  memory:
    auto-sleep:
      enabled: true
      minimum-idle: 5m
      minimum-turns: 5
      retry-interval: 10m
      check-interval: 5s
```

会話の terminal metadata 保存後に対象 Session を登録し、ユーザー・Agent の最終活動から idle 時間を計算します。
他の Agent / background execution がある場合は開始しません。1 回につき既存 Sleep の 1 batch を専用 worker で処理し、
成功・失敗どちらも同じ Session の次の試行まで retry-interval を空けます。未処理件数は永続 Sleep cursor から算出します。
新しい入力・Agent 活動は cancellation guard で検出し、抽出後と transaction 内でもチェックして rollback します。
check-interval ごとの監視で実行スレッドも interrupt します。既存 memory.sleep.* イベントと sleep history で結果を確認できます。

対象登録はプロセス内の直近 256 Session です。再起動後は新たに会話が終わった Session が対象となり、
保存済み cursor から続けます。全 Session の起動時走査、cron、Session 終了時の強制 Sleep は行いません。
idle は Rei が観測した入力・実行を意味し、OS 全体の操作や入力途中のキー操作は観測しません。
手動 Sleep の重複実行制限、件数・入力上限、timeout、永続化と再実行の保証を共有します。

## 制約と拡張

- Session 終了、cron、日次の起動機構はありません。
- 外部 Vector DB、Cross-project 自動検索、自動 archive / forgetting はありません。
- 類似照合の候補は有界です。遠い言い換えや非常に古い記憶を完全に照合する保証はありません。
- LLM の根拠判断の正しさは schema だけでは保証できません。preview と source 追跡で検証してください。
- 単一 Turn が入力上限を超える場合、原典を黙って切り捨てず失敗します。設定上限を調整できます。
- ConversationTurnStore の追記順を cursor とし、既存 Turn の削除・並べ替えを前提としません。
- 将来の trigger は CLI 非依存の SleepService を呼び出せます。検索・モデル呼び出し・永続化も分離しています。
