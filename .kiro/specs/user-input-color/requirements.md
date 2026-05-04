# 要件ドキュメント

## はじめに

rei CLI アプリケーションにおいて、ユーザーが入力したメッセージのブロック表示に色を付け、視認性を向上させる機能を追加する。
現在、ユーザー入力は `┌ User` ヘッダー・メッセージ本文・`└` フッターで構成されるブロックとして出力されているが、色がないため AI の応答と区別しにくい。
JLine3 の `AttributedString` / `AttributedStyle` を使用して ANSI カラーを適用し、新しいライブラリの追加なしに実装する。

## 用語集

- **ReiApplication**: rei CLI アプリケーションのメインクラス。ユーザー入力の受付・表示・コマンド実行を担う。
- **UserInputBlock**: ユーザー入力を囲む表示ブロック。`┌ User` ヘッダー、メッセージ本文、`└` フッターの 3 要素で構成される。
- **AttributedString**: JLine3 が提供する、ANSI エスケープシーケンスを含む文字列表現クラス。
- **AttributedStyle**: JLine3 が提供する、前景色・背景色・太字などのスタイル定義クラス。
- **Terminal**: JLine3 が提供するターミナル抽象クラス。ANSI カラーの出力に使用する。
- **ANSI_COLOR**: ターミナルが解釈する色指定のエスケープシーケンス。

## 要件

### 要件 1: ユーザー入力ブロックへの色付け

**ユーザーストーリー:** 開発者として、ユーザーが入力したメッセージブロックを色付きで表示したい。そうすることで、AI の応答とユーザー入力を一目で区別できる。

#### 受け入れ基準

1. WHEN ユーザーが入力を確定する, THE ReiApplication SHALL `┌ User` ヘッダー行を指定された前景色で出力する
2. WHEN ユーザーが入力を確定する, THE ReiApplication SHALL メッセージ本文の各行を指定された前景色で出力する
3. WHEN ユーザーが入力を確定する, THE ReiApplication SHALL `└` フッター行を指定された前景色で出力する
4. THE ReiApplication SHALL UserInputBlock の色付けに `AttributedString` および `AttributedStyle` を使用する
5. THE ReiApplication SHALL 新しいライブラリを追加せずに色付けを実装する

### 要件 2: ターミナルを通じた色付き出力

**ユーザーストーリー:** 開発者として、色付きの出力が正しく ANSI エスケープシーケンスとしてターミナルに送られてほしい。そうすることで、ターミナルエミュレーターが色を正しく描画できる。

#### 受け入れ基準

1. WHEN UserInputBlock を出力する, THE ReiApplication SHALL `Terminal` の出力ストリームを通じて色付き文字列を書き込む
2. WHEN UserInputBlock を出力する, THE ReiApplication SHALL `AttributedString#toAnsi(Terminal)` を使用して ANSI エスケープシーケンスを生成する
3. IF ターミナルが ANSI カラーをサポートしない場合, THEN THE ReiApplication SHALL 色なしのプレーンテキストとして UserInputBlock を出力する

### 要件 3: 既存の表示フォーマットの維持

**ユーザーストーリー:** 開発者として、色付けを追加した後も既存の表示フォーマット（ブロック構造・改行）が変わらないでほしい。そうすることで、既存の動作を壊さずに機能追加できる。

#### 受け入れ基準

1. THE ReiApplication SHALL 色付け後も UserInputBlock の構造（`┌ User` ヘッダー・本文・`└` フッター）を維持する
2. THE ReiApplication SHALL 複数行入力の各行を個別に色付きで出力する
3. THE ReiApplication SHALL UserInputBlock の前後の空行を維持する
