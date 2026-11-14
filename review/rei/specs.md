# `.kiro/specs` レビュー（仕様文書の整合性）

対象は `.kiro/specs/**` の要件定義書・設計書・実装計画・`.config.kiro`。
すべて静的読解に基づく。テスト・ビルド・実測はしていない。

## 重大度サマリ

| 重大度 | 件数 |
| --- | --- |
| HIGH | 3 |
| MEDIUM | 9 |
| LOW | 6 |

## HIGH

### S-01 `chat-response-narration` の `enabled=false` 時の完了通知が要件と設計で真逆

- `requirements.md` 要件2-2: 「読み上げを実行しなかったとき（…`enabled=false` の場合）、従来どおりコマンド完了通知を実行する」
- `design.md` エラーハンドリング表: 「`enabled=false` の場合はフラグは true に設定される（読み上げ試行済みとして扱う）」→ 完了通知を抑制

音声が出ていないのに完了通知まで消えるため、利用者側の取りこぼしになる。実装前に決着が必要。

### S-02 `command-completion-sound-notification` の追補が本体に存在しないフィールドを参照

- 追補設計（2026-05-08）は「既存の `chatResponseNarrator.wasNarrated()` 判定はそのまま維持し、条件を合成する」と書くが、本体の `executeInterruptibly()` 変更前後に `chatResponseNarrator` は一切登場しない。
- 追補の `shouldSkipCompletionNotification(String... args)` は「先頭引数が `model` または `models`」で判定するため、`--profile x model` のようにグローバルオプションが前置されるとスキップが発動しない。
- プロパティ1「任意のコマンド引数と実行結果に対して常に通知」は、追補の `model`/`models` スキップで反例を持つ。

### S-03 `image-generation` の設定節が重複し、既定出力先が食い違う

- 前半: `output-directory: ${REI_IMAGE_OUTPUT_DIRECTORY:${user.dir}/.rei/images}`
- 後半: `output-directory: ${REI_IMAGE_OUTPUT_DIRECTORY:./images}`

実行ディレクトリ次第で保存先が変わる。`tasks.md` は `.rei/images` を前提にしており、どれが最終仕様か確定していない。

## MEDIUM

### S-04 `.config.kiro` の `specId` が UUID 不正（8件）

`briefing`(`brie`) / `chat`(`chat`) / `config`(`conf`) / `embed`(`embd`) / `feed-management`(`feed`) / `google-calendar`(`gcal`) / `interest-discovery`(`intr`) / `model-management`(`modl`) が `a1b2c3d4-<4文字>-...` 形式で、第2グループが16進数でない。UUID として解釈するツールでは parse エラーになり得る。

### S-05 `specId` の重複

`command-completion-sound-notification` の `specId` が `chat-response-narration` と完全一致（`34c693f6-e736-4187-8978-da77ef918587`）。spec の同一性を保つメタデータの前提を崩す。

### S-06 `command-completion-sound-notification` の主体クラスが要件と設計で矛盾

要件は一貫して `CommandCompletionNotifier` を主体とするが、設計書は「新規クラス不要: `CommandCompletionNotifier` という独立したクラスは作成しない」。

### S-07 `llm-feature-routing` の `temperature` が設計に無い

要件1-4 は `rei.llm.features.<feature>.temperature` を要求するが、設計書の設定例・コンポーネント説明に `temperature` が一切登場しない。

### S-08 フォールバックが設定ミスを隠す（2 spec 共通）

`llm-feature-routing` の `FallbackChatModel` と `image-generation` の `FallbackImageModel` が、失敗条件を限定せず既定接続先へ再送する。誤った `base-url` / `api-key` を隠したまま別の課金先・別モデルへリクエストが飛び得る。

### S-09 `bluesky-posting` の facets タスクが計画に無い

`requirements.md` 要件6（URL/hashtag facet、UTF-8 バイト位置）に対応するタスクが `task.md` にも `tasks.md` にも存在しない。

### S-10 `bluesky-posting` の未定義項目

dry-run 時に `replied_posts` / `daily_count` を更新するか、`daily_count.date` のタイムゾーン、投稿成功後に記録が失敗した場合の補償（二重返信回避）、`generation-timeout-seconds` の実装タスクが未定義。

### S-11 `chat` の要件が他文書と矛盾

- 要件8-9 のサイコロは `tool-taxonomy.md` の「`@Tool` を外し default Tool set から除外」と矛盾。
- 要件8-10「外部プログラムのコマンドが空白を含むときエラー」は字面どおりだと `npm run dev` も拒否する。
- 要件5-1「時間制限で自動中断しない」は `chat-response-narration/design.md` の `streamTimeoutMillis()` タイムアウト分岐と矛盾。

### S-12 `feed-management` の HTTP/XML 防御が要件に無い

タイムアウト・リダイレクト上限・レスポンスサイズ上限・許可スキーム・XXE 対策（DTD/外部実体禁止）が未定義。OPML 取り込み側は上限を明記しているのに、フィード取得側に同種の防御がない。

## LOW

### S-13 `embed` の置換原子性が要件に無い

要件1-5 は「既存を削除してから登録」と読め、途中失敗で既存データを失う。`readme-implementation-notes.md` は `replaceBySource` を1トランザクションと記述しており、要件側に明記すべき。複合スコアの計算式・重みも未定義。

### S-14 `google-calendar` の認証情報保存先が `ReiPaths` と不整合

既定パスが「起動ディレクトリ配下の `.rei/`」で、`rei.data-dir` / OS 別既定値を持つ `ReiPaths` と基準が異なる。トークンのファイルパーミッション・暗号化、OAuth スコープも未定義。

### S-15 `config` のパス解決基準と優先順位

設定ファイルだけ作業ディレクトリ基準で、`ReiPaths` と不整合。外部設定と `application.yaml` の優先順位、テンプレートの API キーをプレースホルダのみとする方針が未定義。

### S-16 `briefing` の時間窓表現が曖昧

要件1-6「昨日 00:00 から翌日 00:00 まで」が読み取りにくく、`feed-management` 要件9-2「昨日 00:00 から現在時刻まで」と食い違う。期限切れタスクが表示順序（要件5）に無い。

### S-17 `chat/design.md` の添付・grep の未定義

`@file` のパス前後空白をトリムしない方針と「空白を含むパス」テストの整合、`@clipboard` 画像のサイズ上限、`grep` の `maxMatches` 既定値・文脈上限・オプション組み合わせ優先順位、プロジェクト外パスの可否が未定義。

### S-18 `model-management/design.md` が追補のみ

本体（`model`/`models` の基本仕様）が無い。`/api/generate` は Ollama 系前提で、OpenAI 互換サーバーでは 404 になり best effort の警告が毎回出る。`keep_alive` の型・意味、タイムアウト、認証ヘッダの秘匿が未定義。

## 確認できた良い点

- `project-work-context/design.md` は履歴テキストをデータとして扱い、永続化前に source ID と origin 主張を検査する方針を明記している。
- `ai-memory-consolidation/tasks.md` が `requirements.md` の `./memory/memory.db` と design の `.rei/memory-consolidation.db` の衝突を「design が正」と決着させている。
- `prompt-time-prefix/design.md` がプロンプト固定文字列テストの破壊リスクと正規表現検証の対策を先回りして書いている。

## 限界

- 本ファイルは `.kiro/specs` の一部のみを対象とする。durable review の作業単位は多数残っており、**未評価の範囲がある**。
- 各評価はレビュー基盤の `findings` ではなく `assessment` テキストとして提出した。座標付き指摘の検証は行っていない。
- 仕様文書の記述と実装コードの突き合わせは、`ReiPaths` / `ReiDataDirectory` / `SqliteVecInstaller` / `ReiApplication` など一部のみ。
