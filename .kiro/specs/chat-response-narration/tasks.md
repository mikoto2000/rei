# 実装計画: ChatCommand 回答音声読み上げ機能

## TDD の進め方

各タスクは **Red → Green → Refactor** サイクルで進める。

1. **Red**: 失敗するテストを先に書く。コンパイルエラーも Red として扱う
2. **Green**: テストが通る最小限の実装を書く。きれいさより動くことを優先する
3. **Refactor**: テストが通ったまま、重複排除・命名改善・設計整理を行う

> テストを書く前に実装を書かない。実装を書く前にテストを書く。

---

## タスク

- [x] 1. ChatResponseNarrator クラスを新規作成する（Red → Green → Refactor）
  - [x] 1.1 **Red**: 失敗するテストを書く
    - `src/test/java/dev/mikoto2000/rei/sound/ChatResponseNarratorTest.java` を新規作成する
    - `SoundNotificationService` を `@Mock` でモック化する
    - 非空テキストで `narrateIfCompleted()` を呼ぶと `soundNotificationService.notify()` が呼ばれることを検証するテストを書く
    - 空文字列で `narrateIfCompleted()` を呼ぶと `soundNotificationService.notify()` が呼ばれないことを検証するテストを書く
    - ブランク文字列で `narrateIfCompleted()` を呼ぶと `soundNotificationService.notify()` が呼ばれないことを検証するテストを書く
    - `reset()` 後に `wasNarrated()` が `false` を返すことを検証するテストを書く
    - この時点で `ChatResponseNarrator` クラスが存在しないためコンパイルエラーになることを確認する
  - [x] 1.2 **Green**: `ChatResponseNarrator` クラスを実装してテストを通す
    - `src/main/java/dev/mikoto2000/rei/sound/ChatResponseNarrator.java` を新規作成する
    - `dev.mikoto2000.rei.sound` パッケージに `@Component` として作成する
    - `AtomicBoolean narratedFlag` フィールドを追加する
    - `reset()`: `narratedFlag` を `false` にリセットする
    - `narrateIfCompleted(String responseText)`: 非空なら `soundNotificationService.notify(responseText)` を呼び出し `narratedFlag` を `true` に設定する。空/ブランクなら `narratedFlag` を `false` に設定する
    - `wasNarrated()`: `narratedFlag.get()` を返す
    - テストがすべて通ることを確認する
  - [x] 1.3 **Refactor**: コードを整理する（任意）
    - null チェックと `isBlank()` チェックの統合を検討する
    - テストが通ったままであることを確認する
  - _Requirements: 1.1, 1.2, 4.1, 4.2, 4.3_

- [ ] 2. ChatResponseNarrator のプロパティテストを追加する
  - [ ]* 2.1 **Property 1: 非空テキストで narrateIfCompleted を呼ぶと読み上げが実行されフラグが true になる**
    - `src/test/java/dev/mikoto2000/rei/sound/ChatResponseNarratorPropertyTest.java` を新規作成する
    - `@ParameterizedTest` + `@MethodSource` で多様な非空テキスト（日本語・英語・記号・長文など 10 パターン）を生成する
    - 各テキストで `narrateIfCompleted()` を呼ぶと `verify(soundNotificationService).notify(responseText)` が通り、`wasNarrated()` が `true` を返すことを検証する
    - **Property 1: 非空テキストで narrateIfCompleted を呼ぶと読み上げが実行されフラグが true になる**
    - **Validates: 要件 1.1, 4.1**
    - タグ: `chatResponseNarration-property-1-nonBlankNarrates`
  - [ ]* 2.2 **Property 2: ブランクテキストで narrateIfCompleted を呼ぶと読み上げが実行されずフラグが false になる**
    - 空文字列・空白文字のみの文字列（スペース・タブ・改行など 10 パターン）を生成する
    - 各テキストで `narrateIfCompleted()` を呼ぶと `verifyNoInteractions(soundNotificationService)` が通り、`wasNarrated()` が `false` を返すことを検証する
    - **Property 2: ブランクテキストで narrateIfCompleted を呼ぶと読み上げが実行されずフラグが false になる**
    - **Validates: 要件 1.2, 4.2**
    - タグ: `chatResponseNarration-property-2-blankSkips`
  - [ ]* 2.3 **Property 3: 連続実行でリセットが正しく機能する**
    - 読み上げあり・なしの組み合わせシーケンス（10 パターン）を生成する
    - 各実行の開始時に `reset()` を呼ぶと `wasNarrated()` が常に `false` を返すことを検証する
    - **Property 3: 連続実行でリセットが正しく機能する**
    - **Validates: 要件 4.3, 4.4**
    - タグ: `chatResponseNarration-property-3-resetIsolates`
  - _Requirements: 1.1, 1.2, 4.1, 4.2, 4.3, 4.4_

- [x] 3. チェックポイント — ChatResponseNarrator のテストがすべて通ることを確認する
  - `./mvnw test "-Dtest=ChatResponseNarrator*"` を実行してすべてのテストが通ることを確認する
  - 失敗するテストがあればここで修正する

- [x] 4. ChatCommand に ChatResponseNarrator を注入する（Red → Green → Refactor）
  - [x] 4.1 **Red**: 既存テストがコンパイルエラーになることを確認する
    - `ChatCommandTest` と `ChatCommandCancellationTest` の `new ChatCommand(...)` 呼び出しが `ChatResponseNarrator` 引数なしで生成していることを確認する
    - `ChatCommand` に `private final ChatResponseNarrator chatResponseNarrator;` フィールドを追加すると `@RequiredArgsConstructor` が生成するコンストラクタの引数が増えてコンパイルエラーになることを確認する
    - この時点でコンパイルエラーになることを確認する
  - [x] 4.2 **Green**: フィールドを追加して既存テストを修正する
    - `src/main/java/dev/mikoto2000/rei/core/command/ChatCommand.java` に `private final ChatResponseNarrator chatResponseNarrator;` フィールドを追加する
    - `ChatCommandTest` の `new ChatCommand(...)` に `Mockito.mock(ChatResponseNarrator.class)` を追加する
    - `ChatCommandCancellationTest` の `new ChatCommand(...)` に `Mockito.mock(ChatResponseNarrator.class)` を追加する
    - 既存テストがコンパイルエラーなく通ることを確認する
  - _Requirements: 1.1, 4.3_

- [x] 5. ChatCommand の run() に reset() と StringBuilder 蓄積を追加する（Red → Green → Refactor）
  - [x] 5.1 **Red**: 失敗するテストを書く
    - `src/test/java/dev/mikoto2000/rei/core/command/ChatCommandNarrationTest.java` を新規作成する
    - `SoundNotificationService` と `ChatResponseNarrator` を `@Mock` でモック化する
    - 正常完了時に `chatResponseNarrator.reset()` が呼ばれることを検証するテストを書く
    - 正常完了時に `chatResponseNarrator.narrateIfCompleted(回答テキスト全文)` が呼ばれることを検証するテストを書く
    - この時点でテストが失敗することを確認する
  - [x] 5.2 **Green**: run() に reset() と StringBuilder 蓄積を追加してテストを通す
    - `run()` 冒頭に `chatResponseNarrator.reset()` を追加する
    - `StringBuilder responseBuilder = new StringBuilder()` を追加する
    - `subscribe()` のチャンク処理に `responseBuilder.append(chunk)` を追加する
    - 正常完了かつエラーなしの場合のみ `chatResponseNarrator.narrateIfCompleted(responseBuilder.toString())` を呼び出す
    - タイムアウト・エラー・キャンセル時は `narrateIfCompleted()` を呼び出さない
    - テストが通ることを確認する
  - [x] 5.3 **Refactor**: コードを整理する（任意）
    - エラー時の `return` 文の前後の流れを整理する
    - テストが通ったままであることを確認する
  - _Requirements: 1.1, 1.3, 1.4, 4.3_

- [ ] 6. ChatCommand のナレーション動作テストを追加する
  - [ ]* 6.1 タイムアウト時に narrateIfCompleted が呼ばれないことを検証する
    - `ChatCommandNarrationTest` に、タイムアウト時に `verifyNoInteractions(chatResponseNarrator)` または `verify(chatResponseNarrator, never()).narrateIfCompleted(any())` が通ることを検証するテストを追加する
    - _Requirements: 1.3_
  - [ ]* 6.2 エラー時に narrateIfCompleted が呼ばれないことを検証する
    - `ChatCommandNarrationTest` に、ストリームエラー時に `verify(chatResponseNarrator, never()).narrateIfCompleted(any())` が通ることを検証するテストを追加する
    - _Requirements: 1.4_
  - [ ]* 6.3 空回答時に narrateIfCompleted が呼ばれるが notify は呼ばれないことを検証する
    - `ChatCommandNarrationTest` に、空文字列の回答時に `verify(chatResponseNarrator).narrateIfCompleted("")` が通り、`verifyNoInteractions(soundNotificationService)` が通ることを検証するテストを追加する
    - _Requirements: 1.2_
  - _Requirements: 1.2, 1.3, 1.4_

- [x] 7. チェックポイント — ChatCommand のテストがすべて通ることを確認する
  - `./mvnw test "-Dtest=ChatCommand*"` を実行してすべてのテストが通ることを確認する
  - 失敗するテストがあればここで修正する

- [x] 8. ReiApplication に ChatResponseNarrator を注入する（Red → Green → Refactor）
  - [x] 8.1 **Red**: 既存テストがコンパイルエラーになることを確認する
    - `ReiApplicationCommandNotificationTest`、`ReiApplicationExitConfirmationTest`、`ReiApplicationInputRenderingTest`、`ReiApplicationColorOutputTest` の `newApp()` ヘルパーが `ChatResponseNarrator` 引数なしで `ReiApplication` を生成していることを確認する
    - `ReiApplication` に `private final ChatResponseNarrator chatResponseNarrator;` フィールドを追加すると `@RequiredArgsConstructor` が生成するコンストラクタの引数が増えてコンパイルエラーになることを確認する
    - この時点でコンパイルエラーになることを確認する
  - [x] 8.2 **Green**: フィールドを追加して既存テストを修正する
    - `src/main/java/dev/mikoto2000/rei/ReiApplication.java` に `private final ChatResponseNarrator chatResponseNarrator;` フィールドを追加する
    - 各テストの `newApp()` に `Mockito.mock(ChatResponseNarrator.class)` を追加する
    - 既存テストがコンパイルエラーなく通ることを確認する
  - _Requirements: 2.1, 2.2, 2.3_

- [x] 9. executeInterruptibly() の finally ブロックに読み上げスキップ制御を追加する（Red → Green → Refactor）
  - [x] 9.1 **Red**: 失敗するテストを書く
    - `src/test/java/dev/mikoto2000/rei/ReiApplicationCommandNotificationTest.java` に以下のテストを追加する
    - `wasNarrated()` が `true` を返す場合にコマンド完了通知がスキップされることを検証するテストを書く（`verify(soundNotificationService, never()).notify("コマンド実行が完了しました")`）
    - `wasNarrated()` が `false` を返す場合にコマンド完了通知が実行されることを検証するテストを書く（`verify(soundNotificationService).notify("コマンド実行が完了しました")`）
    - この時点でテストが失敗することを確認する
  - [x] 9.2 **Green**: finally ブロックに読み上げスキップ制御を追加してテストを通す
    - `executeInterruptibly()` の `finally` ブロックを以下のように変更する
    - `if (!chatResponseNarrator.wasNarrated()) { soundNotificationService.notify(COMMAND_COMPLETION_MESSAGE); }` を追加する
    - `chatResponseNarrator.reset()` を `finally` ブロックの末尾に追加する
    - テストが通ることを確認する
  - [x] 9.3 **Refactor**: コードを整理する（任意）
    - `finally` ブロックの可読性を確認する
    - テストが通ったままであることを確認する
  - _Requirements: 2.1, 2.2, 2.3, 4.3_

- [ ] 10. ReiApplication のナレーション連携テストを追加する
  - [ ]* 10.1 ChatCommand 以外のコマンドではコマンド完了通知が実行されることを検証する
    - `ReiApplicationCommandNotificationTest` に、`ChatResponseNarrator.wasNarrated()` が `false`（デフォルト）の場合にコマンド完了通知が実行されることを検証するテストを追加する
    - _Requirements: 2.3_
  - [ ]* 10.2 finally ブロックで reset() が呼ばれることを検証する
    - `ReiApplicationCommandNotificationTest` に、`executeInterruptibly()` の `finally` ブロックで `chatResponseNarrator.reset()` が呼ばれることを検証するテストを追加する
    - _Requirements: 4.3_
  - _Requirements: 2.3, 4.3_

- [x] 11. 最終チェックポイント — すべてのテストが通ることを確認する
  - `./mvnw test` を実行してすべてのテストが通ることを確認する
  - 失敗するテストがあればここで修正する

---

## 注意事項

- **TDD の鉄則**: テストを書く前に実装を書かない。Red を確認してから Green に進む
- **最小実装**: Green フェーズでは「動く最小限」を書く。きれいにするのは Refactor フェーズ
- **小さいステップ**: 1 つのサブタスクで変更するファイルは原則 1〜2 ファイルに留める
- **SoundNotificationService のモック**: `@Mock` または `Mockito.mock(SoundNotificationService.class)` でモック化し、`verify(soundNotificationService).notify(...)` で検証する
- **ChatResponseNarrator のテスト分離**: `ChatCommandTest`（既存）は変更せず、ナレーション関連テストは `ChatCommandNarrationTest` として別ファイルに実装する
- **ReiApplication のテスト**: 既存の `ReiApplicationCommandNotificationTest` にナレーション関連テストを追加する
- **プロパティテストのタグ名**: 英数字のみ（JUnit 6 のタグ制約に準拠）
- **プロパティテストの実装**: jqwik の `@Property` + `@ForAll` ではなく、`@ParameterizedTest` + `@MethodSource` で実装する（既存プロジェクトのパターンに準拠）
- **ChatCommand 以外のコマンドへの影響**: `ChatResponseNarrator.narrateIfCompleted()` が呼ばれない限り `wasNarrated()` は `false` のため、他コマンドのコマンド完了通知は従来どおり実行される
