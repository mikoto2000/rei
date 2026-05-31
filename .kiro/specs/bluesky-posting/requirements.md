# 要件定義書: Bluesky 投稿機能

## 概要

本機能は、AI エージェント「rei」から Bluesky へテキスト投稿を行う機能を提供する。  
ユーザーは CLI または AI ツール経由で投稿内容を指定し、認証情報に基づいて Bluesky API へ投稿できる。

## 用語定義

- **BlueskyPostService**: Bluesky 投稿 API 呼び出しを担当するサービス。
- **BlueskyProperties**: Bluesky 投稿に必要な設定値（ハンドル、アプリパスワード、投稿上限など）を保持する設定クラス。
- **BlueskyPostTools**: AI エージェントから Bluesky 投稿を呼び出すためのツール。
- **投稿本文**: Bluesky に送信するテキスト。

## 要件

### 要件 1: 投稿機能の有効/無効制御

**ユーザーストーリー:** ユーザーとして、Bluesky 投稿機能を設定で有効化したい。そうすることで、意図しない投稿を防げる。

#### 受け入れ基準

1. WHEN `rei.bluesky.enabled` が `true` のとき、THE system SHALL Bluesky 投稿処理を実行できる。
2. WHEN `rei.bluesky.enabled` が `false` のとき、THE system SHALL Bluesky 投稿処理を実行せず、無効である旨を返す。

---

### 要件 2: 認証情報による投稿

**ユーザーストーリー:** ユーザーとして、設定済みの認証情報で Bluesky に投稿したい。そうすることで、毎回認証を入力せずに利用できる。

#### 受け入れ基準

1. WHEN 投稿要求が行われたとき、THE BlueskyPostService SHALL 設定されたハンドルとアプリパスワードで認証して投稿を実行する。
2. WHEN 認証情報が未設定または不正なとき、THE BlueskyPostService SHALL 投稿を中止し、認証失敗を示すエラーを返す。

---

### 要件 3: 投稿本文バリデーション

**ユーザーストーリー:** システムとして、不正な投稿本文を事前に弾きたい。そうすることで、API エラーや誤投稿を減らせる。

#### 受け入れ基準

1. WHEN 投稿本文が空文字または空白のみのとき、THE BlueskyPostService SHALL 投稿を実行せず、バリデーションエラーを返す。
2. WHEN 投稿本文が設定された最大文字数を超えるとき、THE BlueskyPostService SHALL 投稿を実行せず、文字数超過エラーを返す。
3. WHEN 投稿本文が妥当なとき、THE BlueskyPostService SHALL 投稿を実行する。

---

### 要件 4: AI ツールからの投稿実行

**ユーザーストーリー:** AI エージェントとして、会話中に Bluesky 投稿を実行したい。そうすることで、必要な情報を即時に発信できる。

#### 受け入れ基準

1. THE BlueskyPostTools SHALL 投稿本文を受け取り、BlueskyPostService に委譲して投稿を実行する。
2. WHEN 投稿が成功したとき、THE BlueskyPostTools SHALL 成功を示す結果（投稿 ID または URL を含む）を返す。
3. WHEN 投稿が失敗したとき、THE BlueskyPostTools SHALL 失敗理由を返す。

---

### 要件 5: 例外処理とログ

**ユーザーストーリー:** システムとして、外部 API 障害時も安全に失敗したい。そうすることで、原因調査と再実行判断がしやすくなる。

#### 受け入れ基準

1. WHEN Bluesky API 呼び出しで例外が発生したとき、THE BlueskyPostService SHALL 例外を補足し、失敗結果を返す。
2. WHEN 投稿処理が失敗したとき、THE BlueskyPostService SHALL 原因を識別できるログを出力する（機密情報はログに出力しない）。

---

## 追加要件: Bluesky facets（Hashtag）

### 要件 6: ハッシュタグの facet 付与
**ユーザーストーリー:** ユーザーとして、投稿文に含めた `#ハッシュタグ` を Bluesky 上でリンク化したい。そうすることで、タグ経由で関連投稿に遷移できる。

#### 受け入れ条件
1. WHEN 投稿本文に URL が含まれるとき、THE system SHALL `app.bsky.richtext.facet#link` を付与し、URI と UTF-8 バイト位置（byteStart/byteEnd）を設定する。
2. WHEN 投稿本文に `#tag` 形式のハッシュタグが含まれるとき、THE system SHALL `app.bsky.richtext.facet#tag` を付与し、tag 値（`#` を除いた文字列）と UTF-8 バイト位置（byteStart/byteEnd）を設定する。
3. WHEN URL facet と hashtag facet が同時に存在するとき、THE system SHALL 両方の facet を同一 `facets` 配列に含める。
4. WHEN `#` がハッシュタグとして無効な位置・形式のとき、THE system SHALL hashtag facet を付与しない。
## 追加要件: 対象ユーザー投稿への確率リプライ

### 概要
- リプライ対象は手動指定ではなく、`application.yaml` に定義されたユーザー一覧を使用する。
- 対象ユーザーの新規投稿を定期確認し、ユーザーごとに設定した確率で返信する。

### 設定要件（application.yaml）
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
        - handle: "bob.bsky.social"
          probability: 0.10
          max-replies-per-day: 1
```

### 投稿確認方式
- `app.bsky.feed.getAuthorFeed` を対象ユーザーごとに定期実行する。
- `handle` は必要に応じて `did` に解決して API を呼び出す。
- 前回処理済み境界（`lastSeenPostUri` など）を保持し、増分処理で新着のみを判定する。

### 判定・除外ルール
1. `exclude-reposts=true` の場合は Repost を除外する。
2. `exclude-replies=true` の場合は Reply を除外する。
3. `max-post-age-minutes` を超える古い投稿は除外する。
4. 同一投稿への二重返信は禁止する（既返信記録で判定）。
5. `rand < probability` の場合のみ返信候補とする。
6. `max-replies-per-day` を超える場合は返信しない。

### 実行モード
- `enabled=false` の場合は返信監視・返信投稿を実行しない。
- `dry-run=true` の場合は投稿 API を呼び出さず、判定結果のみログ出力する。

### ログ・監査
- INFO: 対象ユーザー、取得件数、判定件数、返信件数
- DEBUG: 投稿 URI ごとの判定理由（除外理由、確率落選、上限超過）
- 認証情報（app password 等）はログに出力しない。

### 受け入れ基準
1. 設定ユーザー投稿に対し、確率・日次上限どおりに返信判定される。
2. 同一投稿に二重返信しない。
3. `dry-run=true` で外部投稿が 0 件である。
4. `enabled=false` で処理が完全停止する。
