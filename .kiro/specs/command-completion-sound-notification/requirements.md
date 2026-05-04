# 要件定義書: コマンド完了音声通知機能

## はじめに

本機能は、AI エージェント「rei」の CLI コマンドが完了した際に「コマンド実行が完了しました」と音声通知する機能を追加する。
既存の `SoundNotificationService` を活用し、picocli コマンドの実行完了を横断的に検知して音声通知を行う。
音声通知機能の有効/無効は既存の `rei.sound-notification.enabled` 設定に従う。

## 用語集

- **CommandCompletionNotifier**: コマンド完了時に音声通知を実行するコンポーネント。`SoundNotificationService` に委譲して通知を行う。
- **SoundNotificationService**: 既存の音声通知実行サービス。外部プログラム呼び出しとフォールバック処理を担う。
- **SoundNotificationProperties**: 既存の音声通知設定プロパティ。`rei.sound-notification.enabled` および `rei.sound-notification.command` を保持する。
- **コマンド完了通知メッセージ**: コマンド完了時に音声通知するメッセージ。固定値「コマンド実行が完了しました」を使用する。
- **picocli コマンド**: `RootCommand` 配下に登録された CLI コマンド群（`ChatCommand`、`BriefingCommand` など）。

## 要件

### 要件 1: コマンド完了時の音声通知

**ユーザーストーリー:** ユーザーとして、rei の CLI コマンドが完了したときに音声で通知を受け取りたい。そうすることで、コマンドの完了を画面を見ずに把握できる。

#### 受け入れ基準

1. WHEN rei の CLI コマンドが正常に完了したとき、THE CommandCompletionNotifier SHALL `SoundNotificationService` を通じて「コマンド実行が完了しました」と音声通知する
2. WHEN rei の CLI コマンドが例外をスローして終了したとき、THE CommandCompletionNotifier SHALL `SoundNotificationService` を通じて「コマンド実行が完了しました」と音声通知する
3. THE CommandCompletionNotifier SHALL すべての picocli コマンドの完了に対して横断的に通知を実行する（コマンドごとの個別実装は不要）

---

### 要件 2: 音声通知の有効/無効制御との連携

**ユーザーストーリー:** ユーザーとして、既存の音声通知設定でコマンド完了通知の有効/無効を制御したい。そうすることで、設定を一元管理できる。

#### 受け入れ基準

1. WHEN `rei.sound-notification.enabled` が `false` に設定されているとき、THE CommandCompletionNotifier SHALL コマンド完了時に音声通知を実行しない（`SoundNotificationService` のフォールバック動作に委ねる）
2. WHEN `rei.sound-notification.enabled` が `true` に設定されているとき、THE CommandCompletionNotifier SHALL コマンド完了時に音声通知を実行する

---

### 要件 3: 通知メッセージの固定

**ユーザーストーリー:** システムとして、コマンド完了通知のメッセージを固定値にしたい。そうすることで、どのコマンドが完了しても一貫した通知が行われる。

#### 受け入れ基準

1. THE CommandCompletionNotifier SHALL コマンド完了時の通知メッセージとして常に「コマンド実行が完了しました」を使用する
2. THE CommandCompletionNotifier SHALL 通知メッセージをコマンドの種類や実行結果によって変更しない
