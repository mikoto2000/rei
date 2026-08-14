# 要件定義書: 画像生成機能

## 概要

本機能は、AI エージェント「rei」に画像生成専用コマンドを提供する。
ユーザーは通常チャットとは別の `/image generate` コマンドを実行し、プロンプトから画像を生成してローカルファイルとして保存できる。

画像生成は `ChatCommand` とは明確に分離し、テキストチャットのストリーミング応答、会話メモリ、Agent Skills、通常ツール呼び出しとは独立した操作として扱う。

## 用語定義

- **ImageCommand**: 画像生成機能の CLI コマンド。`/image` サブコマンド群を提供する。
- **GenerateCommand**: `/image generate` を担当し、画像生成プロンプトとオプションを受け取るサブコマンド。
- **ImageGenerationService**: 画像生成 API 呼び出し、保存、結果整形を担当するサービス。
- **ImageGenerationClient**: OpenAI 互換または画像生成対応 API との通信を担当するクライアント。
- **ImageGenerationResult**: 成功/失敗、保存パス、エラーメッセージなどを表す結果オブジェクト。
- **機能別 LLM 設定**: `rei.llm.features.image-generation` 配下に定義する画像生成用の接続先設定。

## 要件

### 要件 1: 画像生成専用コマンド

**ユーザーストーリー:** ユーザーとして、通常チャットとは別の明示的なコマンドで画像生成を実行したい。そうすることで、テキスト会話と画像生成操作を混同せずに使える。

#### 受け入れ基準

1. WHEN ユーザーが `/image generate <prompt>` を実行したとき、THE system SHALL `<prompt>` を画像生成リクエストとして受け取る。
2. THE system SHALL 画像生成機能を `ChatCommand` に統合せず、画像生成専用コマンドとして実装する。
3. THE system SHALL `/image generate` 実行時に通常チャットの会話メモリへ画像生成プロンプトまたは結果を追加しない。
4. THE system SHALL `/image generate` 実行時に通常チャット用のストリーミング回答表示を使用しない。
5. WHEN `--raw` が指定されないとき、THE system SHALL `<prompt>` をチャット LLM で画像生成向けプロンプトへ変換してから画像生成 API に渡す。
6. WHEN `--raw` が指定されたとき、THE system SHALL `<prompt>` を変換せず画像生成 API に渡す。
7. THE system SHALL 実際に画像生成 API へ渡したプロンプトを標準出力へ表示する。

---

### 要件 2: 画像ファイルの保存

**ユーザーストーリー:** ユーザーとして、生成された画像をローカルファイルとして保存したい。そうすることで、生成結果を後から確認・共有できる。

#### 受け入れ基準

1. WHEN 画像生成が成功したとき、THE system SHALL 生成画像をローカルファイルとして保存する。
2. WHEN 出力先が指定されないとき、THE system SHALL 既定の画像出力ディレクトリへファイルを保存する。
3. WHEN `--output <path>` が指定されたとき、THE system SHALL 指定パスへ画像を保存する。
4. THE system SHALL 保存に成功した画像ファイルの絶対パスを標準出力へ表示する。
5. THE system SHALL 出力先の親ディレクトリが存在しない場合、自動的に作成する。

---

### 要件 3: 画像生成オプション

**ユーザーストーリー:** ユーザーとして、画像サイズやモデルなどの基本オプションを指定したい。そうすることで、用途に合った画像を生成できる。

#### 受け入れ基準

1. THE GenerateCommand SHALL `--model <model>` を受け取り、画像生成で使用するモデルを上書きできる。
2. THE GenerateCommand SHALL `--size <width>x<height>` を受け取り、画像サイズを指定できる。
3. WHEN `--model` が未指定のとき、THE system SHALL 設定ファイルの画像生成用モデルを使用する。
4. WHEN `--size` が未指定のとき、THE system SHALL 設定ファイルまたは既定値の画像サイズを使用する。
5. WHEN 不正な size 形式が指定されたとき、THE system SHALL 画像生成を実行せず入力エラーを表示する。

---

### 要件 4: 画像生成用接続先の個別指定

**ユーザーストーリー:** ユーザーとして、画像生成だけ別の LLM/画像生成サーバーへ送信したい。そうすることで、通常チャット用サーバーと画像生成用サーバーを分離できる。

#### 受け入れ基準

1. THE system SHALL `rei.llm.features.image-generation.base-url` で画像生成用 API の接続先を指定できる。
2. THE system SHALL `rei.llm.features.image-generation.api-key` で画像生成用 API キーを指定できる。
3. THE system SHALL `rei.llm.features.image-generation.model` で画像生成用モデルを指定できる。
4. WHEN `rei.llm.features.image-generation.base-url` が未指定または空文字のとき、THE system SHALL `spring.ai.openai` で構成された既定接続先を使用する。
5. WHEN 画像生成用接続先への呼び出しが失敗したとき、THE system SHALL 既定接続先へフォールバックする。
6. WHEN フォールバックが発生したとき、THE system SHALL フォールバック発生をログに記録する。
7. THE system SHALL `rei.image.response-format` で画像生成 API へ `response_format` を送信するか制御できる。
8. WHEN `rei.image.response-format` が `auto` のとき、THE system SHALL `gpt-image-*` モデルまたは既定 OpenAI 接続先では `response_format` を送信しない。
9. WHEN `rei.image.response-format` が `b64_json` のとき、THE system SHALL ローカル OpenAI 互換サーバー向けに `response_format: b64_json` を送信する。
10. WHEN `rei.image.response-format` が `none` または `off` のとき、THE system SHALL モデル名に関わらず `response_format` を送信しない。
11. THE system SHALL `rei.image.timeout-seconds` で画像生成 API の読み取りタイムアウトを指定できる。
12. THE system SHALL `rei.llm.features.image-prompt` で画像生成プロンプト生成用チャット LLM の接続先を指定できる。
13. THE system SHALL `rei.image.prompt-enhancement.enabled` で既定のプロンプト生成処理を有効化または無効化できる。

---

### 要件 5: エラーハンドリング

**ユーザーストーリー:** ユーザーとして、画像生成に失敗した理由を把握したい。そうすることで、設定やプロンプトを修正して再実行できる。

#### 受け入れ基準

1. WHEN 画像生成 API 呼び出しで例外が発生したとき、THE system SHALL 異常終了せず失敗メッセージを表示する。
2. WHEN 認証情報が不足または不正なとき、THE system SHALL 認証失敗を示すメッセージを表示する。
3. WHEN API がエラーレスポンスを返したとき、THE system SHALL ステータスまたは原因を含むメッセージを表示する。
4. WHEN 画像データの保存に失敗したとき、THE system SHALL 保存失敗を示すメッセージを表示する。
5. THE system SHALL API キーなどの機密情報を標準出力・ログへ出力しない。

---

### 要件 6: 設定テンプレート

**ユーザーストーリー:** ユーザーとして、`config init` で画像生成に必要な設定例を得たい。そうすることで、設定項目を調べずに機能を有効化できる。

#### 受け入れ基準

1. THE ExternalConfigFileService SHALL テンプレートに `rei.llm.features.image-generation` の設定を含める。
2. THE ExternalConfigFileService SHALL テンプレートに画像生成の既定出力ディレクトリ、既定サイズ、レスポンス形式制御、読み取りタイムアウトの設定を含める。
3. THE ExternalConfigFileService SHALL 画像生成関連の設定値を環境変数で上書き可能な形式で記述する。

---

### 要件 7: 既存機能への非影響

**ユーザーストーリー:** 開発者として、画像生成機能追加で既存のチャット・検索・Bluesky・メモリ機能を壊したくない。そうすることで、安全に機能追加できる。

#### 受け入れ基準

1. THE system SHALL 既存の `ChatCommand` の通常応答挙動を変更しない。
2. THE system SHALL 既存の機能別 LLM ルーティングの挙動を変更しない。
3. THE system SHALL 画像生成機能に関するテストを追加する。
4. THE system SHALL 既存テストが継続して成功することを確認できる。
