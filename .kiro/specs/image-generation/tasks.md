# 実装タスク: 画像生成機能

## 進め方

このタスクは t_wada の TDD に則り、原則として次のサイクルで進める。

1. Red: 期待する振る舞いを表す失敗するテストを先に書く。
2. Green: テストを通すための最小実装を行う。
3. Refactor: 重複、命名、責務分割を整える。

実装は小さな単位で進め、各 Green または Refactor の区切りでテストを確認する。
コミットする場合は、関連するテストと実装を同じコミットに含める。

## タスク一覧

- [x] 1. `ImageSize` の値オブジェクトを追加する
  - Red: `1024x1024` を `width=1024`, `height=1024` として parse できるテストを追加する。
  - Red: `1024`, `1024*1024`, `0x1024`, `1024x0`, `-1x1024` を拒否するテストを追加する。
  - Green: `ImageSize` record と parse 処理を実装する。
  - Refactor: エラーメッセージをユーザー向けに読みやすく整理する。

- [x] 2. `ImageGenerationRequest` / `ImageGenerationResult` を追加する
  - Red: request が prompt / outputPath / model / size を保持できるテストを追加する。
  - Red: result が success / savedPath / message を保持できるテストを追加する。
  - Green: 最小の record を実装する。
  - Refactor: 成功結果・失敗結果の factory method が必要か判断し、必要なら追加する。

- [x] 3. `ImageProperties` を追加する
  - Red: `rei.image.output-directory` と `rei.image.size` を binding できるテストを追加する。
  - Red: 未指定時に `.rei/images` と `1024x1024` を既定値として扱うテストを追加する。
  - Green: `ImageProperties` と configuration properties 登録を実装する。
  - Refactor: パス解決責務を service 側へ寄せ、properties は設定保持に限定する。

- [x] 4. 保存先パス解決を実装する
  - Red: `--output` 指定時に現在の作業ディレクトリ基準で相対パスを絶対化するテストを追加する。
  - Red: `--output` 未指定時に `image-<yyyyMMdd-HHmmss>.png` 形式で既定ディレクトリ配下へ保存するテストを追加する。
  - Green: `ImageOutputPathResolver` または同等の責務を実装する。
  - Refactor: 時刻依存を `Clock` 注入にしてテストを安定化する。

- [x] 5. `ImageGenerationClient` の境界を定義する
  - Red: service から client へ prompt / model / size が渡ることを fake client で検証する。
  - Green: `ImageGenerationClient` interface とテスト用 fake 実装を追加する。
  - Refactor: client の戻り値を base64 画像データに限定し、URL レスポンスは初期実装の対象外にする。

- [x] 6. `ImageGenerationService` の正常系を実装する
  - Red: client が返した base64 PNG を `--output` 指定パスへ保存するテストを追加する。
  - Red: `--output` 未指定時に既定出力ディレクトリへ保存するテストを追加する。
  - Red: 保存先の親ディレクトリが存在しない場合に作成するテストを追加する。
  - Green: 画像生成、base64 decode、ファイル保存、成功結果返却を実装する。
  - Refactor: 入力検証、client 呼び出し、保存処理の責務境界を整理する。

- [x] 7. `ImageGenerationService` の異常系を実装する
  - Red: prompt が空の場合に client を呼ばず失敗結果を返すテストを追加する。
  - Red: client が例外を投げた場合に失敗結果を返すテストを追加する。
  - Red: 空の画像データ、base64 decode 失敗、保存失敗で失敗結果を返すテストを追加する。
  - Green: 異常系ハンドリングを実装する。
  - Refactor: API キーなどの機密情報を message / log に含めない形に整理する。

- [x] 8. Spring AI 連携用 `ImageModelProvider` を実装する
  - Red: `rei.llm.features.image-generation.base-url` が空の場合に既定 `ImageModel` を返すテストを追加する。
  - Red: `base-url` 指定時に画像生成用 `OpenAiImageModel` を構築するテストを追加する。
  - Red: `model` と `api-key` が画像生成用設定から使われるテストを追加する。
  - Green: `ImageModelProvider` を実装する。
  - Refactor: 既存の機能別 LLM 設定クラスとの重複を避ける。

- [x] 9. `FallbackImageModel` を実装する
  - Red: primary の `call(ImagePrompt)` が成功した場合は fallback を呼ばないテストを追加する。
  - Red: primary が失敗した場合に fallback を呼ぶテストを追加する。
  - Red: フォールバック時に primary 用 model を fallback へ引き継がないテストを追加する。
  - Green: `FallbackImageModel` を実装する。
  - Refactor: フォールバック発生の WARN ログを追加し、ログに機密情報を含めない。

- [x] 10. `OpenAiImageGenerationClient` を実装する
  - Red: `ImageGenerationRequest` から `ImagePrompt` と `OpenAiImageOptions` を構築するテストを追加する。
  - Red: model / width / height / response format が options に設定されるテストを追加する。
  - Red: `ImageResponse` に画像データがない場合に失敗扱いにできるテストを追加する。
  - Green: Spring AI `ImageModel` 呼び出しを実装する。
  - Refactor: Spring AI の API 差分に依存する箇所を client 内へ閉じ込める。

- [x] 11. `/image generate` コマンドを追加する
  - Red: RootCommand に `/image` が登録されるテストを追加する。
  - Red: `/image generate <prompt>` が service を呼び、成功時に保存パスを表示するテストを追加する。
  - Red: `--output`, `--model`, `--size` が request に反映されるテストを追加する。
  - Red: 不正な `--size` で画像生成を実行せず `[error]` を表示するテストを追加する。
  - Green: `ImageCommand` と `GenerateCommand` を実装する。
  - Refactor: 通常チャットの入力表示・回答表示・会話メモリへ影響しないことを確認する。

- [x] 12. 設定ファイルと config template を更新する
  - Red: `application.yaml` に `rei.image` と `rei.llm.features.image-generation` が含まれることを確認するテストを追加または更新する。
  - Red: `ExternalConfigFileService` の出力テンプレートに画像生成設定が含まれるテストを追加または更新する。
  - Green: `application.yaml` と `ExternalConfigFileService` を更新する。
  - Refactor: 環境変数名と README の記述が一致するように整理する。

- [x] 13. README を更新する
  - Red: README に `/image generate` の使い方、設定、環境変数が記載されていることを確認する。
  - Green: README を日本語 UTF-8 で更新する。
  - Refactor: 既存の LLM 機能別ルーティング説明と重複しすぎないように整理する。

- [x] 14. 回帰テストを実行する
  - Red: 追加・変更したテストが失敗する状態を確認してから実装する。
  - Green: 画像生成関連テストを成功させる。
  - Green: 既存の関連テストを成功させる。
  - Refactor: 長時間化または外部 API 依存があるテストを mock / fake に置き換える。
  - 完了条件: `JAVA_HOME=C:\Java\jdk-25` を設定して `mvnw.cmd test` が成功する。
