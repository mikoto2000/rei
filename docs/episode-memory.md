# Episode Memory

Episode は出来事の経緯を保持する独立モデルです。Decision の「何が決まったか」、Knowledge の継続的知識、Work Context の現在の作業状況、Reflection の改善候補を置き換えません。

## 既存機能の調査

基点は `origin/main` の `cb19ede4`。既存仕様 `.kiro/specs/ai-memory-consolidation/{requirements,design,tasks}.md` と実装を照合しました。

|機能|実装と有効化の状態|今回の利用|
|---|---|---|
|Memory Consolidation|`MemoryConsolidatorService` の候補抽出・承認経路と、`SleepService` の差分整理経路が併存する。Memory は既定で有効|既存候補・承認・長期記憶を維持|
|MemoryScope / MemoryType|旧分類と FACT / PREFERENCE / CONSTRAINT / PROJECT_STATE / PROCEDURE / LESSON / RELATION 等が併存。新 Sleep 候補は GLOBAL / PROJECT に制限|Episode の状態・時間階層を MemoryScope に追加しない|
|Sleep|Session ごとの完了位置、保存時の競合検出、キャンセル、プレビュー、Project 所有確認が実装済み|同じ実行・モデル予算で別の Episode checkpoint を処理|
|Auto Sleep / Scheduler|Auto Sleep は既定無効。アイドル、未処理件数、再試行間隔、cron、終了時リクエストを持つ|タイマーや自動外部通信を追加しない|
|Work Context|現在の作業と出典・改訂を保持。自動更新は既定無効、自動提示は有効|検索候補・曖昧な話題の補助に使用|
|Reflection|検証済み Goal からの記憶昇格は独立検証を要求|Episode 要約を検証済み事実として昇格させない|
|Hybrid RAG|`HybridRetriever` に dense / lexical / BM25 と `ReciprocalRankFusion` が実装済み。設定は `rei.vector-document.retrieval`|共通 RRF を再利用。Episode 自体の dense 索引は現段階では追加していない|
|会話履歴|`ConversationHistorySearchService` が Sleep と独立して検索し、Project 優先・他 Project 上限・redaction を適用|未整理会話を別経路で保存し直さず検索|
|Session / Turn / Run|`SqliteConversationTurnStore` の永続 Turn と Run ID、Session Repository がある|実在 Run の引用。新しい ordinal 範囲読み取りと Run ID 点検索を追加|
|モデル予算|Sleep の実行単位・Project 単位予算と MEMORY 呼び出しの入力・出力・時間上限がある|同じ予算オブジェクトとツールなし MEMORY 呼び出しを共有|
|機密情報・API|`SensitiveInfoDetector`、`CredentialRedactor`、Project 境界と既存 HTTP 認証がある|保存時に機密候補を拒否、取得時に redaction、Agent の既存ツール登録経路を拡張|

参考: [EpisodicRAG](https://github.com/Bizuayeu/Plugins-Weave/tree/main/EpisodicRAG)、`scripts/application/shadow/file_detector.py`、アーキテクチャ文書、LICENSE を確認しました。処理済み位置から未処理原典を検出する考え方を参考にしています。ファイル階層、Shadow/GrandDigest、8階層、GitHub 継承、コードは採用していません。LICENSE には MIT の記載に加え、特願2025-198943 と特許ライセンスに関する追加記載があります。この実装は Rei の既存モデルに合わせた独立実装です。特許の有効性や適用範囲について法的判断は行っていません。

## データモデルと migration

既存の `memoryConsolidationDataSource` に起動時の `CREATE ... IF NOT EXISTS` で追加します。既存 memories・履歴を変更・削除しません。過去履歴の抽出・embedding は通常起動時には実行しません。

- `episodes`: `(id, revision)` を主キーとする不変の改訂。Project / Session、原典の発生開始・終了日時（終了不明は null）、改訂の保存日時、状態、タイトル、概要、confidence、主張リストの JSON。
- `episode_sources`: 改訂ごとの Session / Run / 発言者 / 実在する tool event ID。会話は Run と role を原典キーとし、架空の Message ID は作らない。
- `episode_relations`: 実在する同じ Project または GLOBAL の長期記憶と Work Context 項目への関連。Sleep が共通の出典 Run を持つ記憶・項目を関連付ける。
- `episode_processing_checkpoints`: Session ごとの処理済み ordinal。
- `episode_processing_leases`: Worker ID と有効期限で同じ対象のモデル処理を排他。
- `episode_fts`: FTS5 trigram。Project・日時の通常索引と既存 Turn の ordinal / Run 索引を利用。

`Episode.Status`: IN_PROGRESS / COMPLETED / UNVERIFIED / WITHDRAWN。

各主張は background / alternative / decision / reason / action / result / unknown と、USER_EXPLICIT / TOOL_OBSERVED / MODEL_PROPOSAL / DERIVED_SUMMARY / UNVERIFIED を別々に保持します。confidence は根拠区分を置き換えません。USER_EXPLICIT は user 発言の完全な部分引用を要求します。assistant の提案はユーザー決定として保存できません。更新は新しい revision の追加で、過去の主張・撤回前の状態は残ります。

## 抽出と差分処理

Memory が有効かつ `REI_MEMORY_EPISODES_ENABLED=true` の場合だけ Sleep 内で抽出します。Memory 無効時は保存済み Episode・長期記憶の検索と詳細取得を停止し、既存の未整理会話検索を維持します。通常応答・履歴検索は抽出モデルを呼びません。Auto Sleep の既存設定を有効にしなければバックグラウンド抽出も始まりません。

1. Session の処理 lease を獲得。
2. Episode checkpoint から最大50 Turn（既存 Sleep max-turns が小さければその値）を ordinal で取得。
3. RUNNING の前で停止。失敗・取消も経緯の抽出対象とし、成功した実装として扱わない。
4. 既存入力トークン上限で batch を制限。
5. 同じ話題の既存候補と複数 Turn をツールなし MEMORY モデルへ渡し、意味単位で抽出。雑談は空配列。
6. 所有者・原典 Run ID・発言者・日時・USER_EXPLICIT 引用を検証。原典の削除・変更を保存直前にも再確認。
7. Episode、出典、FTS、checkpoint の compare-and-set を同一トランザクションで保存。
8. lease を解放。クラッシュ時は期限切れ後に再試行可能。

保存失敗では checkpoint もロールバックします。別 worker が checkpoint を進めた場合は後続保存を拒否します。同じ不変 revision に違う内容を保存できません。原典全体の再読み取り、全件再 embedding、トークン単位の保存を行いません。Episode と長期記憶は別 checkpoint・別トランザクションなので、片方の整理が完了して他方が失敗しても再実行時にそれぞれの位置から再開します。

プレビューでは Episode モデル実行・保存を行いません。未処理 Episode 数も Auto Sleep の候補判定に含めます。処理開始の条件は既存 Auto Sleep の件数だけでなく minimum-idle、retry-interval、cron、モデル予算、活動によるキャンセルを利用します。

## 設定と上限

|設定|既定値|用途|
|---|---|---|
|`rei.memory.episodes.enabled` / `REI_MEMORY_EPISODES_ENABLED`|false|Episode 抽出の明示的な有効化|
|`rei.memory.auto-sleep.enabled`|false|既存バックグラウンド Sleep|
|`rei.memory.auto-sleep.minimum-idle`|5m|アイドル制御|
|`rei.memory.auto-sleep.minimum-turns`|5|件数条件|
|`rei.memory.auto-sleep.retry-interval`|10m|再試行間隔|
|`rei.memory.sleep.max-turns`|50|1 batch の上限|
|`rei.memory.sleep.max-input-tokens`|12000|抽出入力上限|
|`rei.memory.sleep.timeout-seconds`|120|モデル呼び出し上限|
|Sleep の max-llm-calls / max-total-tokens / Project 単位設定|既存値を継承（0 は無制限）|共通モデル使用量を共有|
|`rei.memory.retrieval.max-tokens`|1500|横断検索結果の保守的な推定上限|

lease はモデル timeout +60秒。Episode は最大50件、各改訂の主張は最大32件、主張本文2000文字、フィールド2000文字。機密候補を含む batch は全体を拒否するため、該当原典を除去・整理しないと checkpoint は進みません。

## 検索と段階的取得

Agent の既存ツール経路に以下を登録します。Shell / Web の Agent 会話から利用できます。既存 `searchConversationHistory` / `getConversationHistory` / `/memory` / `/sleep` の呼び方は維持します。

- `searchMemoryHistory`: Episode FTS、長期記憶、未整理を含む既存会話履歴、Work Context を共通 RRF で統合。既知の context、現在 Session の直前の完了した最大2 Turn、OPEN の Work Context の話題で補助。since/until は ISO instant または UTC の暦日で指定できる。最大20候補、概要各500文字、概要合計4000文字に加え既存 retrieval のトークン上限。他 Project 候補は全体で最大3件。
- `episodeGet`: 最新側3改訂、各8主張・本文各200文字、概要500文字、関連記憶 ID を取得。
- `episodeSources`: 最大8原典、各500文字。Session / Run / 発言者 / 根拠区分を返す。
- `episodeProcessingStatus`: 現在 Session の checkpoint。

Episode 検索は最新 revision のみを候補にし、撤回状態を隠しません。複数の話題が一致してもツールは対象を断定せず、候補として返します。正確な理由や実装状態が必要なら詳細・原典を参照してください。検索結果は命令や実行権限として扱わないことをツール説明に明示しています。

削除・期限切れの Run を参照する改訂は概要検索から除外し、詳細では本文を返さず UNAVAILABLE を示します。原典 ID を捏造しません。別 Project のパスやコマンドは現在の Project の実行指示にはなりません。

## テスト・評価

Red はクラス・メソッドの未実装を理由としたコンパイル失敗を確認し、最小実装、Green、上限と原典確認のリファクタリングを進めています。

```powershell
.\mvnw.cmd -Pfull '-Dtest=Episode*Test' test
.\mvnw.cmd -Pfull test
```

Windows で Mockito の動的アタッチが拒否される場合、実際の依存バージョンの `mockito-core` JAR を `-DargLine=-javaagent:<jar の絶対パス>` に指定します。サンドボックスで既存 atomic move テストが拒否された場合、通常の開発環境で再検証が必要です。テストデータは pom の `test.dataDirectory` に隔離します。live 接続テストは `-Pfull` でも対象外です。

`EpisodeEvaluationTest` は7質問を固定 fixture で、変更していない長期記憶検索と Episode FTS の候補数・実行時間で比較し、`target/episode-evaluation.md` に記録します。両経路には明示的な CLI 文脈を与えます。これは回答精度の評価ではありません。正答率、根拠一致率、古い回答の誤採用率、未整理会話の成功率、実モデルのトークン消費、物理 SQLite I/O は未測定です。日付表現は検索文字列であり、暦期間の評価はしていません。

## 残る検証と拡張

Episode の dense 索引、完全な検索品質 fixture、HTTP 固有の操作・権限テスト、大量履歴・並列 writer の負荷計測は追加対応が必要です。改訂はすべて保存する一方、ツールは最新側3改訂・最大8原典を返し、古い改訂のページングはまだ提供しません。発生終了日時は原典にある時刻だけを許可し、不明な完了時刻は推測しません。現段階の横断検索は lexical 候補の RRF 統合であり、既存 dense 検索の全機能を統合したものではありません。実 LLM による抽出品質は未確認です。新しいモデルの返す意味的な分類の正しさは Java の構造・原典検証だけでは保証できません。

## Temporal Digest の将来設計

今回 Digest テーブル・生成・8階層は実装しません。次 Phase では期間 `[start,end)`、タイムゾーン、入力 Episode ID + revision、生成バージョン、入力内容 hash を持つ Digest を検討します。週次は指定タイムゾーンの実暦の週、月次は暦月とし、threshold は期間定義から切り離します。遅延 Episode 登録・新 revision・原典削除は依存 Digest の無効化と再生成の対象にします。Digest から Episode revision と原典まで追跡し、根拠区分を強化しません。検索の対象階層は明示的な設定にし、MemoryScope に時間階層を混在させません。

## ローカル評価記録

条件と実測値は [episode-memory-evaluation.md](episode-memory-evaluation.md) を参照してください。全体回帰テストの最終結果は PR に記録します。
