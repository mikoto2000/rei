# 設計書: ユーザー入力カラー表示機能

## 概要

rei CLI アプリケーションにおいて、ユーザーが入力したメッセージブロック（UserInputBlock）に ANSI カラーを適用し、AI の応答との視認性を向上させる。

既存の `formatUserInput()` メソッドはプレーンテキストを返す設計を維持し、色付き出力は `printUserInput()` メソッドに `Terminal` を受け取るオーバーロードを追加することで実装する。JLine3 の `AttributedString` / `AttributedStyle` を使用するため、新しいライブラリの追加は不要。

## アーキテクチャ

### 変更対象

変更は `ReiApplication` クラスの `printUserInput()` メソッドのみに限定する。

```
ReiApplication
├── formatUserInput(String input): String          ← 変更なし（既存テスト互換）
├── printUserInput(String input)                   ← 変更なし（後方互換のため維持）
└── printUserInput(String input, Terminal terminal) ← 新規追加（色付き出力）
```

`run()` メソッド内の `printUserInput(trimmed)` 呼び出しを `printUserInput(trimmed, terminal)` に変更することで、実際の実行時に色付き出力が使われるようにする。

### データフロー

```mermaid
sequenceDiagram
    participant User
    participant run()
    participant printUserInput(input, terminal)
    participant AttributedStringBuilder
    participant Terminal

    User->>run(): 入力確定
    run()->>printUserInput(input, terminal): trimmed, terminal
    printUserInput(input, terminal)->>AttributedStringBuilder: append(line, CYAN style)
    AttributedStringBuilder->>printUserInput(input, terminal): AttributedString
    printUserInput(input, terminal)->>Terminal: writer().print(toAnsi(terminal))
    Terminal->>User: 色付き出力
```

## コンポーネントとインターフェース

### `printUserInput(String input, Terminal terminal)` の実装

```java
void printUserInput(String input, Terminal terminal) {
    AttributedStringBuilder builder = new AttributedStringBuilder();
    AttributedStyle style = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN);

    builder.append(System.lineSeparator());
    builder.append("┌ User", style);
    builder.append(System.lineSeparator());
    for (String line : input.split("\\R", -1)) {
        builder.append(line, style);
        builder.append(System.lineSeparator());
    }
    builder.append("└", style);
    builder.append(System.lineSeparator());
    builder.append(System.lineSeparator());

    terminal.writer().print(builder.toAnsi(terminal));
    terminal.writer().flush();
}
```

**設計上の判断:**

- `AttributedStyle.CYAN` を使用する。JLine3 の標準色であり、多くのターミナルエミュレーターで視認性が高い。
- 前後の空行（`System.lineSeparator()`）はスタイルを適用しない。空行に色を付けても視覚的な意味がなく、出力が複雑になるだけのため。
- `terminal.writer().flush()` を明示的に呼び出す。JLine3 の `PrintWriter` はバッファリングされる場合があるため。
- `toAnsi(terminal)` を使用することで、dumb terminal（ANSI 非対応）の場合は自動的にプレーンテキストにフォールバックされる。追加のフォールバック処理は不要。

### `run()` メソッドの変更

`run()` 内の `printUserInput(trimmed)` 呼び出しをすべて `printUserInput(trimmed, terminal)` に変更する。

変更箇所（4 か所）:

```java
// 変更前
printUserInput(trimmed);

// 変更後
printUserInput(trimmed, terminal);
```

### 既存メソッドの維持

```java
// 変更なし: 既存テストとの互換性を維持
void printUserInput(String input) {
    System.out.print(formatUserInput(input));
}

// 変更なし: プレーンテキストを返す
String formatUserInput(String input) { ... }
```

## データモデル

### 使用する JLine3 クラス

| クラス | 用途 |
|--------|------|
| `AttributedStringBuilder` | 色付き文字列の構築 |
| `AttributedStyle` | スタイル定義（前景色 CYAN） |
| `Terminal` | ANSI 対応状況の判定と出力先 |

### 色の定義

| 要素 | スタイル |
|------|---------|
| `┌ User` ヘッダー | `AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN)` |
| メッセージ本文（各行） | `AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN)` |
| `└` フッター | `AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN)` |
| 前後の空行 | スタイルなし（プレーンテキスト） |

UserInputBlock 全体を統一した CYAN で色付けすることで、視覚的な一貫性を保つ。

## 正確性プロパティ

*プロパティとは、システムのすべての有効な実行において成立すべき特性または振る舞いのことです。プロパティは人間が読める仕様と機械検証可能な正確性保証の橋渡しをします。*

### Property 1: 色付き出力の構造保存

*任意の* 入力文字列に対して、`printUserInput(input, terminal)` が生成する出力から ANSI エスケープシーケンスを除去した結果は、`formatUserInput(input)` の出力と等しい。

**Validates: Requirements 3.1, 3.2, 3.3**

### Property 2: 色付き出力の ANSI カラーコード含有

*任意の* 非空の入力文字列に対して、ANSI カラー対応の Terminal を使用した場合、`printUserInput(input, terminal)` が Terminal の writer に書き込む文字列は ANSI エスケープシーケンス（`\u001B[` で始まる）を含む。

**Validates: Requirements 1.1, 1.2, 1.3, 2.1, 2.2**

## エラーハンドリング

### dumb terminal（ANSI 非対応）

JLine3 の `toAnsi(terminal)` が自動的に処理する。dumb terminal の場合、`AttributedString#toAnsi(terminal)` はプレーンテキストを返すため、追加のフォールバック処理は不要。

**要件 2.3 はライブラリの機能として自動的に満たされる。**

### Terminal が null の場合

`printUserInput(String input)` の既存オーバーロードが引き続き利用可能なため、Terminal が取得できない状況でも既存の動作にフォールバックできる。ただし、`run()` メソッド内では Terminal は必ず生成されるため、実際には発生しない。

## テスト戦略

### 既存テストへの影響

`ReiApplicationInputRenderingTest` の既存テストは `formatUserInput()` を直接呼び出しており、このメソッドは変更しないため影響なし。

### 新規テスト

#### プロパティベーステスト（jqwik）

プロジェクトには `.jqwik-database` が存在することから、jqwik がすでに使用されている。jqwik を使用してプロパティベーステストを実装する。

**Property 1: 色付き出力の構造保存**

```java
@Property(tries = 100)
// Feature: user-input-color, Property 1: 色付き出力の構造保存
void coloredOutputPreservesStructure(@ForAll @NotEmpty String input) {
    // ANSI 対応の Terminal をモック
    Terminal terminal = mockAnsiTerminal();
    StringWriter captured = captureTerminalOutput(terminal);

    app.printUserInput(input, terminal);

    String coloredOutput = captured.toString();
    String stripped = stripAnsi(coloredOutput);
    assertEquals(app.formatUserInput(input), stripped);
}
```

**Property 2: 色付き出力の ANSI カラーコード含有**

```java
@Property(tries = 100)
// Feature: user-input-color, Property 2: 色付き出力の ANSI カラーコード含有
void coloredOutputContainsAnsiCodes(@ForAll @NotEmpty String input) {
    Terminal terminal = mockAnsiTerminal();
    StringWriter captured = captureTerminalOutput(terminal);

    app.printUserInput(input, terminal);

    String output = captured.toString();
    assertTrue(output.contains("\u001B["));
}
```

#### ユニットテスト（例ベース）

**dumb terminal フォールバック（要件 2.3）**

```java
@Test
void dumbTerminalOutputsPlainText() {
    Terminal dumbTerminal = mockDumbTerminal();
    StringWriter captured = captureTerminalOutput(dumbTerminal);

    app.printUserInput("テスト入力", dumbTerminal);

    String output = captured.toString();
    assertFalse(output.contains("\u001B["));
    assertEquals(app.formatUserInput("テスト入力"), output);
}
```

**Terminal writer への書き込み（要件 2.1）**

```java
@Test
void printUserInputWritesToTerminalWriter() {
    Terminal terminal = mockAnsiTerminal();
    PrintWriter mockWriter = mock(PrintWriter.class);
    when(terminal.writer()).thenReturn(mockWriter);

    app.printUserInput("テスト", terminal);

    verify(mockWriter, atLeastOnce()).print(anyString());
    verify(mockWriter, atLeastOnce()).flush();
}
```

### テスト設定

- プロパティテストは最低 100 回実行（jqwik デフォルト）
- 各プロパティテストにはコメントでデザインドキュメントのプロパティ番号を記載
- タグ形式: `Feature: user-input-color, Property {番号}: {プロパティ内容}`
