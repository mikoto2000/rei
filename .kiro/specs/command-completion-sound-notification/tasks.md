# 実装計画: コマンド完了音声通知機能

## TDD の進め方

各タスクは **Red → Green → Refactor** サイクルで進める。

1. **Red**: 失敗するテストを先に書く。コンパイルエラーも Red として扱う
2. **Green**: テストが通る最小限の実装を書く。きれいさより動くことを優先する
3. **Refactor**: テストが通ったまま、重複排除・命名改善・設計整理を行う

> テストを書く前に実装を書かない。実装を書く前にテストを書く。

---

## タスク

- [x] 1. ReiApplication に SoundNotificationService を注入する（Red → Green）
  - [x] 1.1 **Red**: 既存テストがコンパイルエラーになることを確認する
    - `ReiApplicationExitConfirmationTest` と `ReiApplicationInputRenderingTest` の `newApp()` ヘルパーが `SoundNotificationService` 引数なしで `ReiApplication` を生成していることを確認する
    - `ReiApplication` に `private final SoundNotificationService soundNotificationService;` フィールドを追加すると、`@RequiredArgsConstructor` が生成するコンストラクタの引数が増えてコンパイルエラーになることを確認する
    - この時点でコンパイルエラーになることを確認する
  - [x] 1.2 **Green**: フィールドを追加して既存テストを修正する
    - `src/main/java/dev/mikoto2000/rei/ReiApplication.java` に `private final SoundNotificationService soundNotificationService;` フィールドを追加する
    - `ReiApplicationExitConfirmationTest` の `newApp()` に `Mockito.mock(SoundNotificationService.class)` を追加する
    - `ReiApplicationInputRenderingTest` の `newApp()` に `Mockito.mock(SoundNotificationService.class)` を追加する
    - 既存テストがコンパイルエラーなく通ることを確認する
  - _Requirements: 1.1, 1.3_

- [x] 2. executeInterruptibly() の finally ブロックに通知を追加する（Red → Green → Refactor）
  - [x] 2.1 **Red**: 失敗するテストを書く
    - `src/test/java/dev/mikoto2000/rei/ReiApplicationCommandNotificationTest.java` を新規作成する
    - `executeInterruptibly()` は `private` メソッドのため、テスト用サブクラス `TestableReiApplication` を作成してオーバーライドするか、`protected` に変更してサブクラスからアクセスする
    - 正常完了時（コマンドが `0` を返す）に `verify(soundNotificationService).notify("コマンド実行が完了しました")` が通ることを検証するテストを書く
    - この時点でテストが失敗することを確認する
  - [x] 2.2 **Green**: finally ブロックに 1 行追加してテストを通す
    - `executeInterruptibly()` の `finally` ブロックに `soundNotificationService.notify("コマンド実行が完了しました");` を追加する（`terminal.setAttributes(originalAttributes)` の後）
    - テストが通ることを確認する
  - [x] 2.3 **Refactor**: 通知メッセージを定数に抽出する（任意）
    - `private static final String COMMAND_COMPLETION_MESSAGE = "コマンド実行が完了しました";` として定数化することを検討する
    - テストが通ったままであることを確認する
  - _Requirements: 1.1, 1.3, 3.1, 3.2_

- [x] 3. 例外スロー時にも通知されることをテストで確認する（Red → Green）
  - [x] 3.1 **Red**: 失敗するテストを書く
    - `ReiApplicationCommandNotificationTest` に、コマンド実行が `RuntimeException` をスローした場合でも `verify(soundNotificationService).notify("コマンド実行が完了しました")` が通ることを検証するテストを追加する
    - `CommandLine` の実行をスタブ化して `RuntimeException` をスローさせる
    - この時点でテストが失敗することを確認する（`finally` ブロックへの追加前であれば失敗する）
  - [x] 3.2 **Green**: テストが通ることを確認する
    - タスク 2.2 で追加した `finally` ブロックの実装により、例外スロー時にも通知が呼ばれることを確認する
    - テストが通ることを確認する
  - _Requirements: 1.2_

- [x] 4. チェックポイント — ここまでのテストがすべて通ることを確認する
  - `./mvnw test "-Dtest=ReiApplication*"` を実行してすべてのテストが通ることを確認する
  - 失敗するテストがあればここで修正する

- [ ] 5. ReiApplication — プロパティテストを追加する
  - [ ]* 5.1 **Property 1: コマンド実行結果によらず固定メッセージで通知される**
    - `src/test/java/dev/mikoto2000/rei/ReiApplicationCommandNotificationPropertyTest.java` を新規作成する
    - 様々なコマンド引数（`chat`、`briefing today`、空文字列など 10 パターン）と実行結果（正常完了・`RuntimeException` スロー）の組み合わせで、`notify("コマンド実行が完了しました")` が常に呼ばれることを `@ParameterizedTest` + `@MethodSource` で検証する
    - **Property 1: コマンド実行結果によらず固定メッセージで通知される**
    - **Validates: 要件 1.1, 1.2, 3.1, 3.2**
    - タグ: `command-completion-property-1-alwaysNotify`
  - _Requirements: 1.1, 1.2, 3.1, 3.2_

- [x] 6. 最終チェックポイント — すべてのテストが通ることを確認する
  - `./mvnw test` を実行してすべてのテストが通ることを確認する
  - 失敗するテストがあればここで修正する

---

## 注意事項

- **TDD の鉄則**: テストを書く前に実装を書かない。Red を確認してから Green に進む
- **最小実装**: Green フェーズでは「動く最小限」を書く。きれいにするのは Refactor フェーズ
- **小さいステップ**: 1 つのサブタスクで変更するファイルは原則 1〜2 ファイルに留める
- **executeInterruptibly() のテスト方法**: `private` メソッドのため、`protected` に変更してテスト用サブクラスでオーバーライドするか、テスト用サブクラスで `executeInterruptibly()` を直接呼び出す。`Terminal` は `TerminalBuilder.builder().dumb(true).build()` で生成するか、モック化する
- **SoundNotificationService のモック**: `@Mock` または `Mockito.mock(SoundNotificationService.class)` でモック化し、`verify(soundNotificationService).notify("コマンド実行が完了しました")` で検証する
- **既存テストへの影響**: `ReiApplicationExitConfirmationTest` と `ReiApplicationInputRenderingTest` の `newApp()` ヘルパーは `SoundNotificationService` の引数追加が必要になる。タスク 1.2 で対応する
- プロパティテストのタグ名は英数字のみ（JUnit 6 のタグ制約に準拠）
