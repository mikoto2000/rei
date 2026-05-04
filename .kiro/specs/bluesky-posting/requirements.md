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
