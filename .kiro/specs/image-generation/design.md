# 設計書: 画像生成機能

## 概要

画像生成機能は `/image generate` コマンドで実行する独立機能として実装する。
`ChatCommand` には組み込まず、画像生成用の Service / Client / Properties を分ける。

画像生成 API 呼び出しは Spring AI の `ImageModel` を中心に構成する。
機能別接続先は既存の `rei.llm.features.image-generation` を使用し、通常チャット用の LLM 接続先とは分離する。

## コマンド設計

### `/image`

`ImageCommand` を RootCommand のサブコマンドとして追加する。

```text
/image generate [OPTIONS] <prompt>
```

### `/image generate`

主なオプション:

- `--output <path>`: 保存先ファイルパス。未指定時は既定出力ディレクトリへ保存する。
- `--model <model>`: 画像生成モデル名。未指定時は `rei.llm.features.image-generation.model` を使用する。
- `--size <width>x<height>`: 画像サイズ。未指定時は `rei.image.size` を使用する。

入力ルール:

- `<prompt>` は `arity = "1..*"` とし、複数引数を空白連結して画像生成プロンプトにする。
- `--size` は `1024x1024` のような形式のみ受け付ける。
- `--output` が相対パスの場合は現在の作業ディレクトリ基準で解決する。

出力:

```text
画像を保存しました: <absolute-path>
```

失敗時:

```text
[error] 画像生成に失敗しました: <reason>
```

## 設定

### 画像生成設定

```yaml
rei:
  image:
    output-directory: ${REI_IMAGE_OUTPUT_DIRECTORY:${user.dir}/.rei/images}
    size: ${REI_IMAGE_SIZE:1024x1024}
```

### 接続先設定

```yaml
rei:
  llm:
    features:
      image-generation:
        base-url: ${REI_LLM_IMAGE_GENERATION_BASE_URL:}
        api-key: ${REI_LLM_IMAGE_GENERATION_API_KEY:}
        model: ${REI_LLM_IMAGE_GENERATION_MODEL:}
```

`base-url` が空の場合は `spring.ai.openai` で構成された既定接続先を使用する。

## コンポーネント

### ImageProperties

`rei.image` 配下の設定を保持する。

フィールド:

- `Path outputDirectory`
- `String size`

責務:

- 出力ディレクトリの既定値を提供する。
- 既定サイズを提供する。

### ImageCommand

picocli の `/image` ルートコマンド。

責務:

- `generate` サブコマンドを束ねる。
- 親コマンドとしてのみ動作し、実処理は `GenerateCommand` に委譲する。

### GenerateCommand

`/image generate` の実行を担当する。

責務:

- prompt / output / model / size を受け取る。
- `ImageGenerationService` へ処理を委譲する。
- 成功時に保存パスを表示する。
- 失敗時にユーザー向けエラーを表示する。

### ImageGenerationService

画像生成ユースケースを担当する。

責務:

- `ImageGenerationRequest` を受け取り、入力検証を行う。
- `ImageGenerationClient` を呼び出す。
- 返却された画像データを保存する。
- `ImageGenerationResult` を返す。

### ImageGenerationClient

画像生成 API との通信を担当する。

実装方針:

- Spring AI の `ImageModel` を利用する。
- `OpenAiImageOptions` を使い、model / width / height / response format を指定する。
- 画像データは base64 レスポンスを基本とする。

### ImageModelProvider

画像生成用の `ImageModel` を返す Provider。

責務:

- `rei.llm.features.image-generation` の `base-url` が未指定なら既定の `ImageModel` を返す。
- `base-url` が指定されている場合は `OpenAiImageModel` を生成して返す。
- 機能別画像生成サーバーでは Spring AI 既定の `v1/images/generations` ではなく、`images/generations` を使用する。
- 機能別接続先の呼び出しに失敗した場合は既定 `ImageModel` にフォールバックする。

### FallbackImageModel

機能別 `ImageModel` から既定 `ImageModel` へのフォールバックを担当する。

責務:

- primary の `call(ImagePrompt)` が失敗した場合、fallback の `call(ImagePrompt)` を実行する。
- フォールバック時に機能別 model が prompt options に入っている場合は、既定側へ引き継がない。
- フォールバック発生を WARN ログに記録する。

## データ構造

### ImageGenerationRequest

```java
record ImageGenerationRequest(
    String prompt,
    Path outputPath,
    String model,
    ImageSize size
) {}
```

### ImageSize

```java
record ImageSize(int width, int height) {}
```

責務:

- `1024x1024` 形式の parse を行う。
- width / height が正の整数であることを検証する。

### ImageGenerationResult

```java
record ImageGenerationResult(
    boolean success,
    Path savedPath,
    String message
) {}
```

## 保存設計

保存先の決定:

1. `--output` が指定されている場合はそのパスを使用する。
2. 未指定の場合は `rei.image.output-directory` 配下にファイル名を生成する。

既定ファイル名:

```text
image-<yyyyMMdd-HHmmss>.png
```

保存処理:

- 親ディレクトリが存在しない場合は作成する。
- API から base64 画像データが返った場合は decode して保存する。
- API から URL が返る形式は初期実装では対象外とし、必要になった時点で追加する。

## エラー処理

- prompt が空: 入力エラー
- size が不正: 入力エラー
- API 呼び出し失敗: 失敗結果を返す
- 画像データが空: 失敗結果を返す
- base64 decode 失敗: 失敗結果を返す
- ファイル保存失敗: 失敗結果を返す

API キーや Authorization ヘッダーは標準出力・ログへ出力しない。

## 通知ポリシー

`/image generate` は明示的なコマンドであり、通常チャットの入力表示・回答表示とは分離する。
コマンド完了音声通知については既存の CommandCompletionNotifier の標準ポリシーに従う。
将来必要になった場合は `/image` を通知スキップ対象へ追加できるように、既存のポリシー機構を利用する。

## テスト方針

- `ImageSize` が `1024x1024` を parse できる。
- 不正な size 形式を拒否する。
- `ImageGenerationService` が生成画像を指定パスへ保存する。
- `ImageGenerationService` が未指定出力先を既定ディレクトリへ保存する。
- `ImageGenerationService` が保存先親ディレクトリを作成する。
- `GenerateCommand` が成功時に保存パスを表示する。
- `GenerateCommand` が失敗時に `[error]` を表示する。
- `ImageModelProvider` が `image-generation` の機能別接続先を使用する。
- `FallbackImageModel` が primary 失敗時に既定 `ImageModel` へフォールバックする。
- `ExternalConfigFileService` のテンプレートに `rei.image` と `rei.llm.features.image-generation` が含まれる。
