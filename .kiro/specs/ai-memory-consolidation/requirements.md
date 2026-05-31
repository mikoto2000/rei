# Requirements Document

## Introduction

本機能「AI 睡眠（記憶整理）」は、rei AI エージェントに長期記憶能力を付与するものです。長期にわたる会話や複雑なタスクにおいて、会話履歴・決定事項・タスク・ユーザー設定・プロジェクト文脈を整理し、必要な情報を後続の応答で再利用できるようにします。モデル重みの更新は行わず、外部記憶として SQLite データベース（`./memory/memory.db`）に保存します。

## Glossary

- **Memory_Consolidator**: 会話履歴から重要情報を抽出・分類・保存する記憶整理コンポーネント
- **Memory_Store**: SQLite データベースを操作する永続化コンポーネント（`./memory/memory.db`）
- **Memory_Searcher**: 保存済み記憶を全文検索・ベクトル検索するコンポーネント
- **Memory_Exporter**: 保存済み記憶を Markdown / JSONL 形式へ出力するコンポーネント
- **Memory_Conflict_Resolver**: 既存記憶と新規記憶の重複・矛盾を検出・解消するコンポーネント
- **記憶種別**: 記憶の分類。`user_preference` / `project_context` / `decision` / `task` / `knowledge` / `episode_summary` / `temporary_context` のいずれか
- **スコープ**: 記憶の有効期間。`session` / `short_term` / `project` / `long_term` / `permanent` のいずれか
- **ステータス**: 記憶の状態。`candidate` / `active` / `deprecated` / `archived` / `deleted` のいずれか
- **保存候補**: ユーザーへのレビュー提示前の、ステータスが `candidate` の記憶
- **手動トリガー**: ユーザーが `/memory` コマンドで明示的に実行する操作
- **自動トリガー**: 会話量・コンテキスト長・タスク完了・時間経過などの条件を満たしたときに自動的に発動する整理処理
- **FTS5**: SQLite の全文検索拡張機能（Full-Text Search version 5）
- **信頼度**: 記憶の確からしさを 0.0〜1.0 の数値で表したスコア

---

## Requirements

### Requirement 1: 手動トリガーによる記憶整理

**User Story:** 開発者として、任意のタイミングで `/memory consolidate` コマンドを実行し、現在の会話履歴を整理・要約して保存候補を確認したい。そうすることで、重要な情報を意図的に長期記憶へ昇格させることができる。

#### Acceptance Criteria

1. WHEN ユーザーが `/memory consolidate` コマンドを入力したとき、THE Memory_Consolidator SHALL 現在の会話履歴を取得し、重要情報の抽出処理を開始する
2. WHEN Memory_Consolidator が重要情報の抽出を完了したとき、THE Memory_Consolidator SHALL 抽出した各情報を `user_preference` / `project_context` / `decision` / `task` / `knowledge` / `episode_summary` / `temporary_context` のいずれかの記憶種別に分類する
3. WHEN Memory_Consolidator が記憶種別の分類を完了したとき、THE Memory_Consolidator SHALL 各記憶候補にスコープ（`session` / `short_term` / `project` / `long_term` / `permanent` のいずれか）・信頼度（0.0〜1.0 の数値）・タグを付与し、ステータスを `candidate` として保存候補リストを生成する
4. WHEN 保存候補リストが生成されたとき、THE Memory_Consolidator SHALL 保存候補の内容・記憶種別・スコープ・信頼度をユーザーに提示し、各候補について承認または却下の選択を求める
5. WHEN ユーザーが保存候補の一部または全部を承認したとき、THE Memory_Store SHALL 承認された記憶のステータスを `active` に変更して SQLite データベースへ保存する
6. WHEN ユーザーが保存候補を却下したとき、THE Memory_Consolidator SHALL 却下された保存候補をメモリから破棄し、データベースへ保存しない
7. WHEN 記憶の保存が完了したとき、THE Memory_Consolidator SHALL 保存件数・却下件数・記憶種別の内訳を含む整理結果レポートをユーザーに提示する
8. IF 会話履歴にユーザーまたはアシスタントのメッセージが 1 件も存在しないとき、THEN THE Memory_Consolidator SHALL 「整理対象の会話履歴がありません」というメッセージをユーザーに返し、処理を終了する
9. IF Memory_Store がデータベースへの書き込みに失敗したとき、THEN THE Memory_Store SHALL エラー内容をユーザーに提示し、保存処理をロールバックして記憶のステータスを `candidate` のまま保持する

---

### Requirement 2: 記憶の永続化（SQLite データベース）

**User Story:** 開発者として、整理された記憶を構造化された形式で SQLite データベースに保存したい。そうすることで、セッションをまたいで記憶を参照・再利用できる。

#### Acceptance Criteria

1. THE Memory_Store SHALL `./memory/memory.db` に SQLite データベースファイルを作成・管理する
2. WHEN Memory_Store が初期化されるとき、THE Memory_Store SHALL `memories` / `memory_tags` / `memory_sources` / `memory_relations` / `memory_summaries` / `memory_fts` の 6 テーブルが存在しない場合に自動作成する
3. WHEN Memory_Store が記憶を保存するとき、THE Memory_Store SHALL 記憶本体・記憶種別・スコープ・ステータス・信頼度（0.0〜1.0）・有効期限・作成日時・更新日時を `memories` テーブルに記録する
4. WHEN Memory_Store が記憶を保存するとき、THE Memory_Store SHALL 関連タグを `memory_tags` テーブルに記録する
5. WHEN Memory_Store が記憶を保存するとき、THE Memory_Store SHALL 根拠となる会話・メッセージの参照情報を `memory_sources` テーブルに記録する
6. WHEN Memory_Store が記憶を保存するとき、THE Memory_Store SHALL `memory_fts` テーブルの全文検索インデックスを更新する
7. THE Memory_Store SHALL `./memory/` ディレクトリを独立して管理し、他のアプリケーションファイルと分離された場所に配置する
8. IF データベースファイルが存在しないとき、THEN THE Memory_Store SHALL 起動時にデータベースファイルとテーブルを自動作成する
9. IF Memory_Store がデータベースへの書き込みに失敗したとき、THEN THE Memory_Store SHALL エラー内容をログに記録し、呼び出し元にエラーを通知する

---

### Requirement 3: 記憶の一覧表示

**User Story:** 開発者として、`/memory list` コマンドで保存済み記憶の一覧を確認したい。そうすることで、どのような情報が記憶されているかを把握できる。

#### Acceptance Criteria

1. WHEN ユーザーが `/memory list` コマンドを入力したとき、THE Memory_Store SHALL ステータスが `active` の記憶を作成日時の降順で一覧取得する
2. WHEN Memory_Store が記憶一覧を取得したとき、THE Memory_Consolidator SHALL 記憶 ID・記憶種別・スコープ・内容プレビュー・作成日時を一覧形式でユーザーに提示する。内容プレビューは 100 文字以内とし、100 文字を超える場合は最後の空白または句読点の直前で切り捨てて末尾に「…」を付与する。100 文字以内の場合はそのまま表示する
3. IF ステータスが `active` の記憶が存在しないとき、THEN THE Memory_Consolidator SHALL 「保存済みの記憶はありません」というメッセージをユーザーに返す

---

### Requirement 4: 記憶の検索・復元

**User Story:** 開発者として、`/memory search <query>` コマンドで保存済み記憶を検索し、必要な情報を会話文脈へ復元したい。そうすることで、過去の決定事項やプロジェクト情報を素早く参照できる。

#### Acceptance Criteria

1. WHEN ユーザーが `/memory search <query>` コマンドを入力したとき（`<query>` は 1 文字以上 200 文字以内）、THE Memory_Searcher SHALL 全文検索インデックスを使用して `<query>` に一致するステータスが `active` の記憶を検索する
2. WHEN Memory_Searcher が検索結果を取得したとき、THE Memory_Searcher SHALL 検索エンジンが算出した関連度スコアの降順で最大 10 件の検索結果をユーザーに提示する
3. WHEN ユーザーが検索結果から 1 件の記憶を選択したとき、THE Memory_Searcher SHALL 選択された記憶の内容をプロンプト先頭に追加することで現在の会話文脈へ注入する
4. WHEN Memory_Searcher が記憶を会話文脈へ注入するとき、THE Memory_Searcher SHALL 最大 3 件の記憶のみをプロンプトへ投入する
5. IF 検索クエリに一致するステータスが `active` の記憶が存在しないとき、THEN THE Memory_Searcher SHALL 「該当する記憶が見つかりませんでした」というメッセージをユーザーに返す
6. IF `<query>` が空文字または空白のみのとき、THEN THE Memory_Searcher SHALL 「検索クエリを入力してください」というメッセージをユーザーに返し、検索処理を実行しない
7. IF `<query>` が 200 文字を超えるとき、THEN THE Memory_Searcher SHALL 「検索クエリは 200 文字以内で入力してください」というメッセージをユーザーに返し、検索処理を実行しない

---

### Requirement 5: 記憶の削除（論理削除）

**User Story:** 開発者として、`/memory forget <id>` コマンドで不要な記憶を削除したい。そうすることで、古くなった情報や誤った情報を記憶から除外できる。

#### Acceptance Criteria

1. WHEN ユーザーが `/memory forget <id>` コマンドを入力したとき、THE Memory_Store SHALL 指定された ID の記憶のステータスを `deleted` に更新し、以降の一覧・検索結果から除外する（物理削除は行わず、データベース上にレコードを保持する）
2. WHEN 記憶の論理削除が完了したとき、THE Memory_Consolidator SHALL 「記憶 `<id>` を削除しました」というメッセージをユーザーに提示する
3. IF 指定された ID の記憶が存在しないとき、THEN THE Memory_Store SHALL 「指定された ID の記憶が見つかりません」というエラーメッセージをユーザーに返す
4. IF 指定された ID の記憶のステータスがすでに `deleted` のとき、THEN THE Memory_Store SHALL 「指定された記憶はすでに削除済みです」というメッセージをユーザーに返す
5. IF `<id>` が空文字・空白のみ・または有効な ID 形式でないとき、THEN THE Memory_Store SHALL 「有効な記憶 ID を指定してください」というエラーメッセージをユーザーに返し、処理を実行しない

---

### Requirement 6: 矛盾・重複の検出と解消

**User Story:** 開発者として、新しい記憶が既存の記憶と矛盾・重複する場合に検出・解消したい。そうすることで、記憶の一貫性を保ち、誤った情報に基づく応答を防ぐことができる。

#### Acceptance Criteria

1. WHEN Memory_Store が新規記憶を保存するとき、THE Memory_Conflict_Resolver SHALL 同一スコープ・同一記憶種別の既存記憶との類似度を検査し、類似度スコアが 0.8 以上かつ内容が相反する場合を「矛盾」、0.95 以上かつ内容が同一の場合を「重複」として判定する
2. WHEN Memory_Conflict_Resolver が矛盾する記憶を検出したとき（類似度スコア ≥ 0.8 かつ相反する内容）、THE Memory_Conflict_Resolver SHALL 矛盾の内容をユーザーに提示し、「既存記憶を更新する」「既存記憶を `deprecated` にする」「両方を保持して適用条件を明記する」のいずれかの解消方法をユーザーに選択させる
3. WHEN Memory_Conflict_Resolver が重複する記憶を検出したとき（類似度スコア ≥ 0.95 かつ同一の内容）、THE Memory_Conflict_Resolver SHALL 重複内容をユーザーに提示し、「新規記憶を破棄する」「既存記憶を新規内容で置き換える」のいずれかをユーザーに選択させる
4. WHEN ユーザーが既存記憶を `deprecated` にすることを選択したとき、THE Memory_Store SHALL 既存記憶のステータスを `deprecated` に更新し、物理削除は行わない
5. WHEN ユーザーが解消方法を選択したとき、THE Memory_Store SHALL `memory_relations` テーブルに `updates` または `contradicts` の関係種別で記憶間の関係を記録する
6. IF ユーザーが矛盾・重複の解消方法を選択せずに一定時間（60 秒）が経過したとき、THEN THE Memory_Conflict_Resolver SHALL 新規記憶の保存を中断し、「タイムアウトにより保存をキャンセルしました」というメッセージをユーザーに返す

---

### Requirement 7: 自動トリガーによる記憶整理

**User Story:** 開発者として、一定の条件を満たしたときに自動的に記憶整理が提案されるようにしたい。そうすることで、手動操作を忘れた場合でも重要な情報を見逃さずに済む。

#### Acceptance Criteria

1. WHEN 会話メッセージ数が設定された閾値（デフォルト: 20 件、設定可能範囲: 1〜1000 件）を超えたとき、THE Memory_Consolidator SHALL 記憶整理の実行をユーザーに提案する
2. WHEN 会話のコンテキスト長が設定された上限の 80% を超えたとき、THE Memory_Consolidator SHALL 記憶整理の実行をユーザーに提案する
3. WHEN ユーザーまたはシステムがタスクの完了を明示的に宣言したとき、THE Memory_Consolidator SHALL 記憶整理の実行をユーザーに提案する
4. WHEN ユーザーが重要な決定事項を明示的に表明したとき、THE Memory_Consolidator SHALL 記憶整理の実行をユーザーに提案する
5. WHILE 自動トリガーが発動しているとき、THE Memory_Consolidator SHALL 即時保存は行わず、整理対象の候補件数を含む保存候補の提示に留める
6. IF ユーザーが自動トリガーによる提案を明示的に否定応答したとき、THEN THE Memory_Consolidator SHALL 整理処理を中断し、同一のトリガー条件が再度成立するまで再提案しない

---

### Requirement 8: 記憶のエクスポート

**User Story:** 開発者として、`/memory export` コマンドで保存済み記憶を Markdown または JSONL 形式でエクスポートしたい。そうすることで、記憶の内容を外部ツールで参照・バックアップできる。

#### Acceptance Criteria

1. WHEN ユーザーが `/memory export` コマンドを入力したとき、THE Memory_Exporter SHALL ステータスが `active` の記憶を全件取得する
2. WHEN Memory_Exporter が記憶を取得したとき、THE Memory_Exporter SHALL `./exports/latest.md` に Markdown 形式で出力する（既存ファイルは上書きする）
3. WHEN Memory_Exporter が記憶を取得したとき、THE Memory_Exporter SHALL `./exports/yyyy-mm-dd.md` および `./exports/yyyy-mm-dd.jsonl` に日付付きファイルとして出力する（`yyyy-mm-dd` は実行日の日付。同日に複数回実行した場合は上書きする）
4. WHEN エクスポートが完了したとき、THE Memory_Exporter SHALL 出力ファイルのパスと出力件数をユーザーに提示する
5. IF エクスポート対象のステータスが `active` の記憶が存在しないとき、THEN THE Memory_Exporter SHALL 「エクスポート対象の記憶がありません」というメッセージをユーザーに返す
6. IF ファイルへの書き込みに失敗したとき、THEN THE Memory_Exporter SHALL エラー内容（ファイルパスと失敗理由）をユーザーに提示し、処理を中断する

---

### Requirement 9: 会話要約の生成と保存

**User Story:** 開発者として、`/memory summarize` コマンドで現在の会話を要約し、`episode_summary` として保存したい。そうすることで、長い会話の要点を後から参照できる。

#### Acceptance Criteria

1. WHEN ユーザーが `/memory summarize` コマンドを入力したとき、THE Memory_Consolidator SHALL 現在の会話履歴全体を 2000 文字以内で要約する
2. WHEN Memory_Consolidator が要約を生成したとき、THE Memory_Consolidator SHALL 要約内容をユーザーに提示し、その後 `episode_summary` として保存するかどうかの確認（「保存する」または「保存しない」）をユーザーに求める
3. WHEN ユーザーが要約の保存を承認したとき、THE Memory_Store SHALL 要約を `memory_summaries` テーブルおよび `memories` テーブルに `episode_summary` 種別で保存する
4. IF Memory_Store がデータベースへの書き込みに失敗したとき、THEN THE Memory_Store SHALL エラー内容をユーザーに提示し、保存処理をロールバックする
5. IF ユーザーが要約の保存を拒否したとき、THEN THE Memory_Consolidator SHALL 要約をメモリから破棄し、データベースへ保存しない
6. IF 会話履歴にユーザーまたはアシスタントのメッセージが 1 件も存在しないとき、THEN THE Memory_Consolidator SHALL 「要約対象の会話履歴がありません」というメッセージをユーザーに返し、処理を終了する

---

### Requirement 10: 個人情報・機密情報の保護

**User Story:** 開発者として、個人情報や機密情報が含まれる記憶を保存する前に明示的な確認を受けたい。そうすることで、意図しない機密情報の永続化を防ぐことができる。

#### Acceptance Criteria

1. WHEN Memory_Consolidator が保存候補を生成するとき、THE Memory_Consolidator SHALL 以下のパターンに該当する情報を含む記憶を検出する: メールアドレス形式の文字列、電話番号形式の数字列、`password` / `secret` / `token` / `api_key` / `apikey` を含むキーと値のペア、`-----BEGIN` で始まる PEM 形式の文字列
2. WHEN Memory_Consolidator が上記パターンに該当する記憶を検出したとき、THE Memory_Consolidator SHALL 該当記憶に `⚠️ 機密情報の可能性` という警告ラベルを付与し、保存前に「この記憶には機密情報が含まれる可能性があります。保存しますか？（保存する / 保存しない）」という確認をユーザーに求める
3. WHILE ユーザーが個人情報・機密情報を含む記憶の保存確認に応答していないとき、THE Memory_Store SHALL 該当記憶をデータベースへ保存しない
4. IF ユーザーが機密情報を含む記憶の保存を拒否したとき、THEN THE Memory_Consolidator SHALL 該当記憶をメモリから破棄し、データベースへ保存しない

---

### Requirement 11: 記憶の有効期限管理

**User Story:** 開発者として、スコープに応じた有効期限を記憶に設定したい。そうすることで、`temporary_context` などの短期的な記憶が自動的に無効化され、記憶の鮮度を保つことができる。

#### Acceptance Criteria

1. WHEN Memory_Store が記憶を保存するとき、THE Memory_Store SHALL スコープに応じたデフォルト有効期限を設定する（`session`: Memory_Store のセッション終了イベント発生時、`short_term`: 保存日から 30 日後、`project`: プロジェクトスコープの削除イベント発生時、`long_term`: 保存日から 365 日後、`permanent`: 無期限）
2. WHEN Memory_Store が記憶を取得するとき、THE Memory_Store SHALL 有効期限が現在日時を過ぎた記憶のステータスを `deprecated` に更新し、`deprecated` ステータスを明示的に指定しないクエリの結果に含めない
3. WHEN ユーザーが有効期限を明示的に指定して記憶を保存するとき、THE Memory_Store SHALL デフォルト有効期限の代わりにユーザー指定の有効期限を設定する
4. IF ユーザーが現在日時より過去の日時を有効期限として指定したとき、THEN THE Memory_Store SHALL 「有効期限には現在日時より未来の日時を指定してください」というエラーメッセージをユーザーに返し、保存処理を実行しない
