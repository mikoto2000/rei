# 要件定義書: ChatCommand 回答音声読み上げ機能

## はじめに

本機能は、AI エージェント「rei」の `ChatCommand` が AI から受け取った回答テキストを `SoundNotificationService` で音声読み上げする機能を追加する。
音声読み上げが実行された場合は、`ReiApplication.executeInterruptibly()` の `finally` ブロックで行われるコマンド完了音声通知（「コマンド実行が完了しました」）を抑制する。
これにより、ユーザーは AI の回答を音声で受け取りつつ、重複した音声通知を避けられる。

## 用語集

- **ChatCommand**: AI との対話を行う picocli コマンド。AI の回答をストリームで受け取り標準出力に出力する。
- **ChatResponseNarrator**: `ChatCommand` の AI 回答テキストを `SoundNotificationService` を通じて音声読み上げするコンポーネント。読み上げ前にテキストのサニタイズも担う。
- **SoundNotificationService**: 既存の音声通知実行サービス。外部プログラム呼び出しとフォールバック処理を担う。
- **SoundNotificationProperties**: 既存の音声通知設定プロパティ。`rei.sound-notification.enabled` および `rei.sound-notification.command` を保持する。
- **コマンド完了通知**: `ReiApplication.executeInterruptibly()` の `finally` ブロックで実行される「コマンド実行が完了しました」の音声通知。`command-completion-sound-notification` スペックで実装済み。
- **読み上げスキップフラグ**: `ChatCommand` が音声読み上げを実行したことを `ReiApplication` に伝えるためのフラグ。コマンド完了通知の抑制に使用する。
- **AI 回答テキスト**: `ChatCommand` がストリームで受け取り、標準出力に出力する AI の回答全文。
- **サニタイズ**: 音声読み上げに不適切な文字（絵文字・制御文字・非表示文字など）を除去する前処理。外部 TTS プログラムが特殊文字で誤動作するのを防ぐ。

## 要件

### 要件 1: ChatCommand の AI 回答音声読み上げ

**ユーザーストーリー:** ユーザーとして、`chat` コマンドの AI 回答を音声で聞きたい。そうすることで、画面を見ずに AI の回答内容を把握できる。

#### 受け入れ基準

1. WHEN `ChatCommand` が AI 回答のストリームを正常に受信し完了したとき、THE ChatResponseNarrator SHALL `SoundNotificationService` を通じて AI 回答テキスト全文を音声読み上げする
2. WHEN AI 回答テキストが空文字列またはブランクのとき、THE ChatResponseNarrator SHALL 音声読み上げを実行しない
3. WHEN `ChatCommand` がタイムアウトまたはキャンセルにより正常完了しなかったとき、THE ChatResponseNarrator SHALL 音声読み上げを実行しない
4. WHEN `ChatCommand` がエラーにより回答取得に失敗したとき、THE ChatResponseNarrator SHALL 音声読み上げを実行しない
5. WHILE `rei.sound-notification.enabled` が `false` に設定されているとき、THE ChatResponseNarrator SHALL 音声読み上げを実行しない（`SoundNotificationService` のフォールバック動作に委ねる）

---

### 要件 5: 音声読み上げ前のテキストサニタイズ

**ユーザーストーリー:** システムとして、外部 TTS プログラムが誤動作しないよう、音声読み上げ前に AI 回答テキストから不適切な文字・Markdown 記法を除去したい。そうすることで、絵文字・制御文字・コードブロック・見出しなどが含まれていても読み上げが途中で止まらない。

#### 受け入れ基準

1. WHEN 音声読み上げを実行するとき、THE ChatResponseNarrator SHALL `SoundNotificationService.notify()` を呼び出す前に AI 回答テキストをサニタイズする
2. THE ChatResponseNarrator SHALL サニタイズ処理で Markdown コードブロック（` ``` ` で囲まれたブロック全体）を除去する
3. THE ChatResponseNarrator SHALL サニタイズ処理でバッククォート1つのインラインコード（`` `code` ``）はバッククォートを除去してテキストを残す
4. THE ChatResponseNarrator SHALL サニタイズ処理で見出し（`#` 〜 `######`）の記号を除去してテキストを残す
5. THE ChatResponseNarrator SHALL サニタイズ処理で太字（`**text**`、`__text__`）の記号を除去してテキストを残す
6. THE ChatResponseNarrator SHALL サニタイズ処理で斜体（`*text*`、`_text_`）の記号を除去してテキストを残す
7. THE ChatResponseNarrator SHALL サニタイズ処理で引用（`> text`）の `>` を除去してテキストを残す
8. THE ChatResponseNarrator SHALL サニタイズ処理で水平線（`---`、`***` のみの行）を行ごと除去する
9. THE ChatResponseNarrator SHALL サニタイズ処理で表（`|` を含む行）を保持する
10. THE ChatResponseNarrator SHALL サニタイズ処理でリンク（`[text](url)`）を `text` のみに変換する
11. THE ChatResponseNarrator SHALL サニタイズ処理で箇条書き（`- item`）をそのまま残す
12. THE ChatResponseNarrator SHALL サニタイズ処理で箇条書き（`* item`）の `*` を `-` に置換して残す
13. THE ChatResponseNarrator SHALL サニタイズ処理で絵文字・記号文字（Unicode So カテゴリ）を除去する
14. THE ChatResponseNarrator SHALL サニタイズ処理でサロゲートペア文字（Unicode Cs カテゴリ）を除去する
15. THE ChatResponseNarrator SHALL サニタイズ処理で未割当文字（Unicode Cn カテゴリ）を除去する
16. THE ChatResponseNarrator SHALL サニタイズ処理で制御文字（U+0000〜U+0008、U+000B、U+000C、U+000E〜U+001F、U+007F）を除去する
17. THE ChatResponseNarrator SHALL サニタイズ処理でタブ（U+0009）・改行（U+000A）・復帰（U+000D）を保持する
18. WHEN サニタイズ後のテキストが空またはブランクになったとき、THE ChatResponseNarrator SHALL 音声読み上げを実行しない

---

### 要件 2: 音声読み上げ実行時のコマンド完了通知の抑制

**ユーザーストーリー:** ユーザーとして、AI 回答が音声読み上げされた場合に「コマンド実行が完了しました」という通知が重複して流れないようにしたい。そうすることで、音声通知が二重になる煩わしさを避けられる。

#### 受け入れ基準

1. WHEN `ChatCommand` が AI 回答の音声読み上げを実行したとき、THE ReiApplication SHALL `executeInterruptibly()` の `finally` ブロックでコマンド完了通知（「コマンド実行が完了しました」）を実行しない
2. WHEN `ChatCommand` が音声読み上げを実行しなかったとき（タイムアウト・キャンセル・エラー・空回答・`enabled=false` の場合）、THE ReiApplication SHALL 従来どおりコマンド完了通知を実行する
3. WHEN `ChatCommand` 以外の picocli コマンドが実行されたとき、THE ReiApplication SHALL 従来どおりコマンド完了通知を実行する

---

### 要件 3: 音声読み上げ有効/無効の設定連携

**ユーザーストーリー:** ユーザーとして、既存の音声通知設定で AI 回答の音声読み上げの有効/無効を制御したい。そうすることで、設定を一元管理できる。

#### 受け入れ基準

1. WHEN `rei.sound-notification.enabled` が `true` に設定されているとき、THE ChatResponseNarrator SHALL AI 回答テキストを音声読み上げする
2. WHEN `rei.sound-notification.enabled` が `false` に設定されているとき、THE ChatResponseNarrator SHALL 音声読み上げを実行しない
3. THE ChatResponseNarrator SHALL 音声読み上げの有効/無効判定を `SoundNotificationService` に委譲し、独自の有効/無効チェックを行わない

---

### 要件 4: 読み上げスキップフラグの管理

**ユーザーストーリー:** システムとして、`ChatCommand` が音声読み上げを実行したかどうかを `ReiApplication` が確実に把握できるようにしたい。そうすることで、コマンド完了通知の抑制を正確に制御できる。

#### 受け入れ基準

1. THE ChatResponseNarrator SHALL 音声読み上げを実行した場合に読み上げスキップフラグを `true` に設定する
2. THE ChatResponseNarrator SHALL 音声読み上げを実行しなかった場合に読み上げスキップフラグを `false` に設定する
3. THE ChatResponseNarrator SHALL `ChatCommand` の実行開始時に読み上げスキップフラグを `false` にリセットする
4. WHEN 複数の `ChatCommand` が連続して実行されるとき、THE ChatResponseNarrator SHALL 前回の実行の読み上げスキップフラグが次回の実行に影響しないことを保証する
