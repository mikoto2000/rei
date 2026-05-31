# 設計書: Bluesky 投稿・確率リプライ機能

## 1. 目的
本設計は、既存の Bluesky 投稿機能に加え、`application.yaml` で定義した対象ユーザーの投稿を定期監視し、ユーザーごとの確率で自動リプライする機能を提供する。

## 2. スコープ
- 対象ユーザー投稿の定期確認
- 確率判定による返信可否決定
- 日次上限と重複返信防止
- dry-run モード
- 監査ログ出力

非スコープ:
- 高度なスパム判定
- 投稿内容の品質評価モデル
- 分散実行前提の厳密排他

## 3. アーキテクチャ

### 3.1 コンポーネント
- `BlueskyReplyProperties`
  - `rei.bluesky.reply` 配下の設定を保持
- `BlueskyReplyScheduler`
  - 定期実行エントリポイント
- `BlueskyReplyService`
  - ユーザー巡回、投稿取得、判定、返信実行
- `BlueskyAuthorFeedClient`
  - `app.bsky.feed.getAuthorFeed` 呼び出し
- `BlueskyReplyStateRepository`
  - 増分境界・既返信・日次件数の永続化
- `BlueskyPostService`（既存）
  - 実際の返信投稿 (`reply.root` / `reply.parent` を含む)

### 3.2 既存連携
- 既存の URL facet / hashtag facet 生成は `BlueskyPostService` 側の処理を流用
- 認証は既存 Bluesky 認証設定（handle/app password）を流用

## 4. 設定モデル

```yaml
rei:
  bluesky:
    reply:
      enabled: true
      dry-run: false
      check-interval-seconds: 300
      fetch-limit: 30
      exclude-replies: true
      exclude-reposts: true
      max-post-age-minutes: 120
      users:
        - handle: "alice.bsky.social"
          probability: 0.25
          max-replies-per-day: 3
```

### 4.1 バリデーション
- `users[].handle`: 必須、空禁止
- `users[].probability`: 必須、`0.0 <= p <= 1.0`
- `check-interval-seconds`: `>= 10`
- `fetch-limit`: `1..100`
- `max-replies-per-day`: `>= 0`

## 5. データ設計

### 5.1 `bluesky_reply_user_state`
- `handle` (PK)
- `last_seen_post_uri`
- `last_seen_indexed_at`
- `updated_at`

用途: 増分処理の境界管理

### 5.2 `bluesky_replied_posts`
- `post_uri` (PK)
- `handle`
- `replied_post_uri`
- `replied_at`

用途: 二重返信防止

### 5.3 `bluesky_reply_daily_count`
- `handle`
- `date` (YYYY-MM-DD)
- `count`
- PK(`handle`, `date`)

用途: 日次返信上限管理

## 6. 処理フロー

1. `enabled=false` なら即終了
2. 設定ユーザーを順次処理
3. `handle -> did` 解決
4. `getAuthorFeed(actor=did, limit=fetchLimit)` 取得
5. 新着投稿のみ抽出（`last_seen_*` 境界で判定）
6. 除外判定
   - repost 除外 (`exclude-reposts`)
   - reply 除外 (`exclude-replies`)
   - 古い投稿除外 (`max-post-age-minutes`)
   - 既返信除外 (`bluesky_replied_posts`)
7. 確率判定（`ThreadLocalRandom.current().nextDouble() < probability`）
8. 日次上限判定（`count < maxRepliesPerDay`）
9. `dry-run=true` ならログのみ
10. `dry-run=false` なら `BlueskyPostService` 経由で返信投稿
11. 成功時に `replied_posts` と `daily_count` を更新
12. 最後に `last_seen_*` 更新

## 7. 返信生成
- 返信対象投稿の `uri` / `cid` を使用
- `reply.parent` = 対象投稿
- `reply.root` = 対象が root なら同じ、thread 内なら root を使用
- 本文は既存生成ロジック（必要なら別途プロンプト）
- facet は既存実装（URL/hashtag）を適用

## 8. エラーハンドリング
- ユーザー単位で隔離して処理（1ユーザー失敗で全体停止しない）
- API エラー時:
  - WARN ログ
  - 当該ユーザーのみスキップして次へ
- 永続化エラー時:
  - ERROR ログ
  - 再実行時に整合が崩れないよう、書き込み順序を `投稿成功 -> replied_posts -> daily_count` に固定

## 9. ログ設計
- INFO
  - `handle`, `fetched`, `candidates`, `replied`, `skipped`
- DEBUG
  - 投稿 URI 単位の除外理由（repost/reply/old/already_replied/probability/daily_limit）
- 機密情報（app password, token）は出力しない

## 10. テスト戦略

### 10.1 単体テスト
- 確率判定ロジック
- 除外条件判定
- 日次上限判定
- dry-run 時の未投稿保証

### 10.2 リポジトリテスト
- `replied_posts` 一意制約
- `daily_count` upsert
- `last_seen` 更新

### 10.3 サービステスト（モック）
- feed API レスポンスから候補抽出
- 投稿成功/失敗分岐
- ユーザーごとの障害分離

### 10.4 受け入れ相当
- 設定2ユーザーで確率と上限どおり動作
- 同一投稿に二重返信しない

## 11. 将来拡張
- 返信対象時間帯の設定
- クールダウン（ユーザーごと最小返信間隔）
- 返信本文テンプレートのユーザー別設定
