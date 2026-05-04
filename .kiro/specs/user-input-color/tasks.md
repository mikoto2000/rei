# 実装計画: ユーザー入力カラー表示機能

## TDD の進め方

各タスクは **Red → Green → Refactor** サイクルで進める。

1. **Red**: 失敗するテストを先に書く。コンパイルエラーも Red として扱う
2. **Green**: テストが通る最小限の実装を書く。きれいさより動くことを優先する
3. **Refactor**: テストが通ったまま、重複排除・命名改善・設計整理を行う

> テストを書く前に実装を書かない。実装を書く前にテストを書く。

---

## タスク

- [x] 1. `printUserInput(String, Terminal)` を追加する（Red → Green → Refactor）
  - [x] 1.1 **Red**: 失敗するテストを書く
    - `src/test/java/dev/mikoto2000/rei/ReiApplicationColorOutputTest.java` を新規作成する
    - ANSI 対応の Terminal をモック化し、`StringWriter` で出力をキャプチャするヘルパーメソッド `mockAnsiTerminal()` / `captureTerminalOutput(terminal)` を用意する
    - `app.printUserInput("テスト", terminal)` を呼び出すと `terminal.writer()` に対して `print()` と `flush()` が少なくとも 1 回呼ばれることを検証するテスト `printUserInputWritesToTerminalWriter` を書く
    - この時点で `printUserInput(String, Terminal)` が存在しないためコンパイルエラー（Red）になることを確認する
    - _Requirements: 2.1_
  - [x] 1.2 **Green**: メソッドを追加してテストを通す
    - `ReiApplication` に `void printUserInput(String input, Terminal terminal)` を追加する
    - `AttributedStringBuilder` でブロック全体を `AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN)` で色付けし、`terminal.writer().print(builder.toAnsi(terminal))` と `terminal.writer().flush()` で出力する
    - テストがコンパイルエラーなく通ることを確認する
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 2.1, 2.2_
  - [x] 1.3 **Refactor**: スタイル定義を変数に抽出する（任意）
    - `AttributedStyle style = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN);` をメソッド先頭で定義し、各 `append` 呼び出しで再利用する
    - テストが通ったままであることを確認する
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 2.1, 2.2_

- [x] 2. dumb terminal でプレーンテキストにフォールバックすることをテストで確認する（Red → Green）
  - [x] 2.1 **Red**: 失敗するテストを書く
    - `ReiApplicationColorOutputTest` に `dumbTerminalOutputsPlainText` テストを追加する
    - `TerminalBuilder.builder().dumb(true).build()` で dumb terminal を生成し、`StringWriter` で出力をキャプチャする
    - `app.printUserInput("テスト入力", dumbTerminal)` の出力が `\u001B[` を含まないこと、かつ `app.formatUserInput("テスト入力")` と等しいことを検証する
    - この時点でテストが失敗することを確認する（タスク 1.2 の実装前であれば失敗する）
    - _Requirements: 2.3_
  - [x] 2.2 **Green**: テストが通ることを確認する
    - タスク 1.2 で追加した `toAnsi(terminal)` の実装により、dumb terminal では自動的にプレーンテキストが返されることを確認する
    - テストが通ることを確認する
    - _Requirements: 2.3_
  - _Requirements: 2.3_

- [x] 3. チェックポイント — ここまでのテストがすべて通ることを確認する
  - `./mvnw test "-Dtest=ReiApplication*"` を実行してすべてのテストが通ることを確認する
  - 失敗するテストがあればここで修正する

- [x] 4. `run()` 内の呼び出しを `printUserInput(trimmed, terminal)` に変更する（Red → Green）
  - [x] 4.1 **Red**: 変更前の状態を確認する
    - `run()` 内に `printUserInput(trimmed)` が 4 か所あることを確認する
    - この時点では既存の `printUserInput(String)` が呼ばれており、色付き出力は行われていない
    - _Requirements: 2.1_
  - [x] 4.2 **Green**: 4 か所の呼び出しを変更する
    - `run()` 内の `printUserInput(trimmed)` をすべて `printUserInput(trimmed, terminal)` に変更する（4 か所）
    - `./mvnw test "-Dtest=ReiApplication*"` を実行して既存テストが引き続き通ることを確認する
    - _Requirements: 1.1, 1.2, 1.3, 2.1, 2.2, 3.1, 3.2, 3.3_
  - _Requirements: 1.1, 1.2, 1.3, 2.1, 2.2, 3.1, 3.2, 3.3_

- [ ] 5. プロパティテストを追加する
  - [ ]* 5.1 **Property 1: 色付き出力の構造保存**
    - `src/test/java/dev/mikoto2000/rei/ReiApplicationColorOutputPropertyTest.java` を新規作成する
    - jqwik の `@Property(tries = 100)` と `@ForAll @NotEmpty String input` を使用する
    - ANSI 対応の Terminal をモック化し、`StringWriter` で出力をキャプチャする
    - `app.printUserInput(input, terminal)` の出力から ANSI エスケープシーケンス（`\u001B\[[^m]*m` または `AttributedString.fromAnsi(str).toString()`）を除去した結果が `app.formatUserInput(input)` と等しいことを検証する
    - **Property 1: 色付き出力の構造保存**
    - **Validates: 要件 3.1, 3.2, 3.3**
    - タグ: `userInputColorProperty1StructurePreservation`
    - _Requirements: 3.1, 3.2, 3.3_
  - [ ]* 5.2 **Property 2: 色付き出力の ANSI カラーコード含有**
    - `ReiApplicationColorOutputPropertyTest` に `coloredOutputContainsAnsiCodes` テストを追加する
    - jqwik の `@Property(tries = 100)` と `@ForAll @NotEmpty String input` を使用する
    - ANSI 対応の Terminal をモック化し、`StringWriter` で出力をキャプチャする
    - `app.printUserInput(input, terminal)` が Terminal の writer に書き込む文字列が `\u001B[` を含むことを検証する
    - **Property 2: 色付き出力の ANSI カラーコード含有**
    - **Validates: 要件 1.1, 1.2, 1.3, 2.1, 2.2**
    - タグ: `userInputColorProperty2AnsiCodePresence`
    - _Requirements: 1.1, 1.2, 1.3, 2.1, 2.2_
  - _Requirements: 1.1, 1.2, 1.3, 2.1, 2.2, 3.1, 3.2, 3.3_

- [x] 6. 最終チェックポイント — すべてのテストが通ることを確認する
  - `./mvnw test` を実行してすべてのテストが通ることを確認する
  - 失敗するテストがあればここで修正する

---

## 注意事項

- **TDD の鉄則**: テストを書く前に実装を書かない。Red を確認してから Green に進む
- **最小実装**: Green フェーズでは「動く最小限」を書く。きれいにするのは Refactor フェーズ
- **小さいステップ**: 1 つのサブタスクで変更するファイルは原則 1〜2 ファイルに留める
- **ANSI 対応 Terminal のモック**: `TerminalBuilder.builder().system(false).streams(inputStream, outputStream).build()` などで生成するか、Mockito でモック化する。`terminal.writer()` への書き込みを `StringWriter` でキャプチャして検証する
- **dumb terminal の生成**: `TerminalBuilder.builder().dumb(true).build()` で生成する。`toAnsi(terminal)` が自動的にプレーンテキストを返す
- **ANSI コードの除去**: `AttributedString.fromAnsi(str).toString()` または正規表現 `\u001B\[[^m]*m` で除去する
- **既存テストへの影響**: `ReiApplicationInputRenderingTest` は `formatUserInput()` を直接テストしており変更不要。`printUserInput(String)` も変更しないため影響なし
- **`run()` の変更**: `printUserInput(trimmed)` の呼び出しは 4 か所あるため、すべて `printUserInput(trimmed, terminal)` に変更する
- プロパティテストのタグ名は英数字のみ（JUnit 6 のタグ制約に準拠）
