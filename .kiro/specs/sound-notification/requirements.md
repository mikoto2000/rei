# 要件定義書: 音声通知機能

## はじめに

本機能は、AI エージェント「rei」に音声通知機能を提供する。
通知メッセージを外部プログラムに渡すことで音声読み上げなどの通知を実現し、外部プログラムは YAML 設定ファイルでコマンドとして定義できる。
外部プログラムが未設定・無効・実行失敗・タイムアウトの場合は、標準出力への通知にフォールバックする。

## 用語集

- **SoundNotificationService**: 音声通知の実行を担うサービス。設定に基づいて外部プログラムを呼び出し、失敗時は標準出力通知へフォールバックする。
- **SoundNotificationProperties**: 音声通知の設定プロパティクラス。`rei.sound-notification` 配下の設定値を保持する。
- **SoundNotificationTools**: AI エージェントから音声通知を呼び出すためのツール群。
- **SoundInterestNotifier**: 興味関心更新情報を音声通知で伝える `InterestNotifier` 実装。音声通知後にコンソールにも出力する。
- **外部プログラム**: `rei.sound-notification.command` で定義された、通知メッセージを受け取って音声出力などを行うプログラム。
- **`{{MESSAGE}}`**: コマンド引数内に記述するプレースホルダー。実行時に通知メッセージに置換される。
- **標準出力通知**: 音声通知のフォールバック手段。通知メッセージを標準出力に出力する。

## 要件

### 要件 1: 音声通知の有効/無効制御

**ユーザーストーリー:** ユーザーとして、音声通知の有効/無効を設定で切り替えたい。そうすることで、環境に応じて音声通知を使用するかどうかを制御できる。

#### 受け入れ基準

1. WHEN `rei.sound-notification.enabled` が `true` に設定されているとき、THE SoundNotificationService SHALL 外部プログラムを使用して通知を実行する
2. WHEN `rei.sound-notification.enabled` が `false` に設定されているとき、THE SoundNotificationService SHALL 標準出力通知へフォールバックする
3. WHEN `rei.sound-notification.enabled` が `false` に設定されているとき、THE SoundNotificationService SHALL フォールバックの理由を warn ログに出力する

---

### 要件 2: コマンド未設定時のフォールバック

**ユーザーストーリー:** システムとして、外部プログラムのコマンドが設定されていない場合に安全にフォールバックしたい。そうすることで、設定不備があっても通知が失われない。

#### 受け入れ基準

1. WHEN `rei.sound-notification.command` が設定されていないとき、THE SoundNotificationService SHALL 標準出力通知へフォールバックする
2. WHEN `rei.sound-notification.command` が設定されていないとき、THE SoundNotificationService SHALL フォールバックの理由を warn ログに出力する

---

### 要件 3: `{{MESSAGE}}` プレースホルダーの置換

**ユーザーストーリー:** システムとして、コマンド引数内の `{{MESSAGE}}` を通知メッセージに置換して外部プログラムを実行したい。そうすることで、任意のコマンド構成で通知メッセージを外部プログラムに渡せる。

#### 受け入れ基準

1. WHEN 外部プログラムを実行するとき、THE SoundNotificationService SHALL `rei.sound-notification.command` リスト内のすべての要素に含まれる `{{MESSAGE}}` を通知メッセージに置換する
2. WHEN `rei.sound-notification.command` のいずれの要素にも `{{MESSAGE}}` が含まれていないとき、THE SoundNotificationService SHALL warn ログを出力してそのままコマンドを実行する

---

### 要件 4: 外部プログラム呼び出し失敗時のフォールバック

**ユーザーストーリー:** システムとして、外部プログラムの実行に失敗した場合に安全にフォールバックしたい。そうすることで、外部プログラムの障害があっても通知が失われない。

#### 受け入れ基準

1. WHEN 外部プログラムの実行が失敗したとき、THE SoundNotificationService SHALL 標準出力通知へフォールバックする
2. WHEN 外部プログラムの実行が失敗したとき、THE SoundNotificationService SHALL 失敗の理由を warn ログに出力する

---

### 要件 5: 外部プログラムのタイムアウト

**ユーザーストーリー:** システムとして、外部プログラムが長時間応答しない場合にタイムアウトしたい。そうすることで、通知処理が無限に待機状態にならない。

#### 受け入れ基準

1. WHEN 外部プログラムの実行が 5 分以内に完了しないとき、THE SoundNotificationService SHALL 外部プログラムを強制終了して標準出力通知へフォールバックする
2. WHEN タイムアウトが発生したとき、THE SoundNotificationService SHALL タイムアウトの旨を warn ログに出力する

---

### 要件 6: AI エージェントからの音声通知呼び出し

**ユーザーストーリー:** AI エージェントとして、会話の中で音声通知を実行したい。そうすることで、ユーザーへの重要な通知を音声で伝えられる。

#### 受け入れ基準

1. THE SoundNotificationTools SHALL メッセージ文字列を受け取り、SoundNotificationService を通じて通知を実行するツールを AI エージェントに提供する
2. WHEN AI エージェントが音声通知ツールを呼び出したとき、THE SoundNotificationTools SHALL 通知の成否を呼び出し元に返す

---

### 要件 7: 興味関心更新の音声通知

**ユーザーストーリー:** システムとして、興味関心更新情報を音声で通知したい。そうすることで、ユーザーが画面を見ていなくても新しい興味関心情報を把握できる。

#### 受け入れ基準

1. THE SoundInterestNotifier SHALL `InterestNotifier` を実装し、`@Primary` として登録される
2. WHEN 興味関心更新情報が通知されるとき、THE SoundInterestNotifier SHALL トピック名と要約を結合したメッセージを SoundNotificationService を通じて音声通知する
3. WHEN 音声通知が実行されたとき、THE SoundInterestNotifier SHALL コンソールにも同じ内容を出力する（`ConsoleInterestNotifier` に委譲する）
