# 要件定義書: Google Calendar 連携機能

## はじめに

本機能は、AI エージェント「rei」に Google Calendar との連携機能を提供する。
ユーザーは AI との会話を通じてカレンダーの予定を参照・作成でき、日次ブリーフィングにも今日の予定が自動的に含まれる。

## 用語集

- **GoogleCalendarService**: Google Calendar API を使用して予定の取得・作成を担うサービス。
- **GoogleCalendarTools**: AI エージェントから Google Calendar 操作を呼び出すためのツール群。
- **GoogleCalendarEventSummary**: カレンダーイベントの概要データクラス（ID・タイトル・開始日時・終了日時・場所・ステータス）。
- **GoogleCalendarProperties**: Google Calendar 連携の設定（有効フラグ・認証情報パス・タイムゾーン・デフォルトカレンダー ID）。
- **OAuth2 認証**: Google API へのアクセスに使用する OAuth2 認証フロー。

## 要件

### 要件 1: 指定期間の予定一覧取得

**ユーザーストーリー:** ユーザーとして、指定した期間の Google Calendar 予定を確認したい。そうすることで、スケジュールを AI との会話で把握できる。

#### 受け入れ基準

1. WHEN ユーザーが開始日時と終了日時を指定して予定一覧操作を実行したとき、THE GoogleCalendarService SHALL 指定期間の予定を開始時刻昇順で返す
2. THE GoogleCalendarService SHALL 各予定について ID・タイトル・開始日時・終了日時・場所・ステータスを返す
3. WHEN Google Calendar 連携が無効のとき、THE GoogleCalendarService SHALL エラーを返す

---

### 要件 2: 指定日の予定一覧取得

**ユーザーストーリー:** システムとして、指定した日付の予定を取得したい。そうすることで、日次ブリーフィングに今日の予定を含められる。

#### 受け入れ基準

1. WHEN 日付が指定されたとき、THE GoogleCalendarService SHALL 設定されたタイムゾーンでその日の 00:00 から翌日 00:00 までの予定を返す
2. WHEN タイムゾーンが設定されていないとき、THE GoogleCalendarService SHALL システムデフォルトのタイムゾーンを使用する

---

### 要件 3: 予定の作成

**ユーザーストーリー:** ユーザーとして、AI との会話で Google Calendar に予定を作成したい。そうすることで、カレンダーアプリを開かずに予定を追加できる。

#### 受け入れ基準

1. WHEN ユーザーがタイトル・開始日時・終了日時を指定して予定作成操作を実行したとき、THE GoogleCalendarService SHALL Google Calendar に予定を作成する
2. WHEN 終了日時が開始日時以前のとき、THE GoogleCalendarService SHALL エラーを返す
3. WHERE 場所が指定されたとき、THE GoogleCalendarService SHALL 場所を予定に設定する
4. WHERE 説明が指定されたとき、THE GoogleCalendarService SHALL 説明を予定に設定する
5. THE GoogleCalendarService SHALL 作成された予定の概要を返す

---

### 要件 4: 日時の解析

**ユーザーストーリー:** システムとして、様々な形式の日時文字列を解析したい。そうすることで、ユーザーが柔軟な形式で日時を指定できる。

#### 受け入れ基準

1. THE GoogleCalendarService SHALL ISO-8601 形式（タイムゾーン付き）の日時文字列を解析できる
2. THE GoogleCalendarService SHALL ISO-8601 形式（オフセット付き）の日時文字列を解析できる
3. THE GoogleCalendarService SHALL ローカル日時形式（タイムゾーンなし）の日時文字列を設定タイムゾーンとして解析できる
4. WHEN 日時文字列がいずれの形式にも一致しないとき、THE GoogleCalendarService SHALL エラーを返す

---

### 要件 5: OAuth2 認証

**ユーザーストーリー:** システムとして、Google Calendar API へのアクセスに OAuth2 認証を使用したい。そうすることで、ユーザーの Google アカウントに安全にアクセスできる。

#### 受け入れ基準

1. WHEN Google Calendar API にアクセスするとき、THE GoogleCalendarService SHALL 設定された認証情報ファイルを使用して OAuth2 認証を実行する
2. WHEN 認証情報ファイルが存在しないとき、THE GoogleCalendarService SHALL エラーを返す
3. THE GoogleCalendarService SHALL 取得したトークンを設定されたディレクトリに保存して再利用する
4. WHEN ブラウザが利用可能なとき、THE GoogleCalendarService SHALL OAuth2 認証 URL をブラウザで自動的に開く
5. WHEN ブラウザが利用できないとき、THE GoogleCalendarService SHALL OAuth2 認証 URL をコンソールに表示する
6. THE GoogleCalendarService SHALL 認証情報ファイルのデフォルトパスを起動ディレクトリ配下の `.rei/google-calendar-credentials.json` とする
7. THE GoogleCalendarService SHALL OAuth トークンのデフォルト保存先を起動ディレクトリ配下の `.rei/google-calendar-tokens` とする
