# 実装タスク: Bluesky 投稿・確率リプライ機能

## 方針
- 小さな単位で実装し、各ステップでテストを追加する。
- 既存 `BlueskyPostService` を活用しつつ、返信監視機能を段階的に追加する。

## タスク一覧

- [x] 1. 設定クラスを追加する
  - [x] 1.1 `BlueskyReplyProperties` を作成する
    - `rei.bluesky.reply` をマッピング
    - `enabled`, `dryRun`, `checkIntervalSeconds`, `fetchLimit`, `excludeReplies`, `excludeReposts`, `maxPostAgeMinutes`, `users` を定義
  - [x] 1.2 `users` の要素クラスを定義する
    - `handle`, `probability`, `maxRepliesPerDay` を定義
  - [x] 1.3 バリデーションを実装する
    - `handle` 必須
    - `probability` は `0.0..1.0`
    - `checkIntervalSeconds >= 10`
    - `fetchLimit in 1..100`
    - `maxRepliesPerDay >= 0`

- [x] 2. 永続化テーブルを追加する
  - [x] 2.1 `bluesky_reply_user_state` を作成する
  - [x] 2.2 `bluesky_replied_posts` を作成する
    - `post_uri` 一意制約
  - [x] 2.3 `bluesky_reply_daily_count` を作成する
    - 複合主キー `handle, date`

- [x] 3. リポジトリ層を実装する
  - [x] 3.1 `BlueskyReplyStateRepository` を作成する
    - `findLastSeen(handle)`
    - `saveLastSeen(handle, postUri, indexedAt)`
  - [x] 3.2 既返信管理を実装する
    - `isAlreadyReplied(postUri)`
    - `markReplied(postUri, handle, repliedPostUri)`
  - [x] 3.3 日次件数管理を実装する
    - `countToday(handle, date)`
    - `incrementToday(handle, date)`

- [x] 4. Author Feed クライアントを実装する
  - [x] 4.1 `BlueskyAuthorFeedClient` を作成する
    - `getAuthorFeed(actorDid, limit)` を実装
  - [x] 4.2 `handle -> did` 解決を実装する
    - 失敗時はユーザー単位でスキップ可能にする

- [x] 5. 返信判定ロジックを実装する
  - [x] 5.1 投稿除外条件を実装する
    - repost 除外
    - reply 除外
    - 古い投稿除外
    - 既返信除外
  - [x] 5.2 確率判定を実装する
    - `rand < probability`
  - [x] 5.3 日次上限判定を実装する

- [x] 6. 返信実行サービスを実装する
  - [x] 6.1 `BlueskyReplyService` を作成する
    - ユーザー巡回
    - feed 取得
    - 新着抽出
    - 判定
    - 投稿実行
  - [x] 6.2 `dry-run` モードを実装する
    - 投稿 API を呼ばずログのみ
  - [x] 6.3 返信投稿の root/parent 設定を実装する
    - `reply.parent`
    - `reply.root`

- [x] 7. スケジューラを実装する
  - [x] 7.1 `BlueskyReplyScheduler` を作成する
    - `checkIntervalSeconds` で定期実行
  - [x] 7.2 `enabled=false` 時の停止挙動を実装する

- [x] 8. ログを実装する
  - [x] 8.1 INFO ログを追加する
    - `handle`, `fetched`, `candidates`, `replied`, `skipped`
  - [x] 8.2 DEBUG ログを追加する
    - 投稿 URI 単位の除外理由
  - [x] 8.3 機密情報を出力しないことを確認する

- [x] 9. テストを実装する
  - [x] 9.1 設定バリデーションテスト
  - [x] 9.2 リポジトリテスト
    - 一意制約
    - upsert
    - 境界更新
  - [x] 9.3 判定ロジック単体テスト
    - repost/reply/age/既返信/確率/上限
  - [x] 9.4 サービステスト（モック）
    - 正常系
    - API エラー系
    - ユーザー障害分離
    - dry-run
  - [x] 9.5 既存 Bluesky 投稿テストの回帰確認

- [x] 10. 最終確認
  - [x] 10.1 全体テスト実行
  - [x] 10.2 ドキュメント整合確認
    - `requirements.md` / `design.md` / `tasks.md`

## 完了条件
- 設定ユーザー投稿に対し、確率・日次上限に従って返信判定できる。
- 同一投稿への二重返信が発生しない。
- `dry-run=true` で外部投稿が発生しない。
- `enabled=false` で処理が停止する。
- テストが通る。
