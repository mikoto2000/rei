# 要件定義書: Model 管理機能

## はじめに

本機能は、AI エージェント「rei」に使用する AI モデルの確認・切り替え機能を提供する。
ユーザーはコマンドラインから現在使用中のモデルを確認し、OpenAI 互換 API で利用可能なモデル一覧を表示して、使用するモデルを動的に切り替えられる。

## 用語集

- **ModelCommand**: 現在使用中のチャットモデルの確認・変更を行うコマンド。
- **ModelsCommand**: OpenAI 互換 API で利用可能なモデルの一覧を表示するコマンド。
- **ModelHolderService**: 現在選択中のチャットモデル名をスレッドセーフに保持するサービス。
- **OpenAiCompatibleModelsService**: OpenAI 互換 API の `/v1/models` エンドポイントからモデル一覧を取得するサービス。
- **デフォルトモデル**: `spring.ai.openai.chat.options.model` プロパティで設定された初期モデル。

## 要件

### 要件 1: 現在のモデル確認

**ユーザーストーリー:** ユーザーとして、現在使用中の AI モデルを確認したい。そうすることで、どのモデルで応答が生成されているかを把握できる。

#### 受け入れ基準

1. WHEN ユーザーが `model` コマンドを引数なしで実行したとき、THE ModelCommand SHALL 現在選択中のモデル名を標準出力に表示する
2. THE ModelHolderService SHALL アプリケーション起動時に `spring.ai.openai.chat.options.model` プロパティの値をデフォルトモデルとして設定する

---

### 要件 2: モデルの切り替え

**ユーザーストーリー:** ユーザーとして、使用する AI モデルを動的に切り替えたい。そうすることで、タスクに応じて最適なモデルを選択できる。

#### 受け入れ基準

1. WHEN ユーザーが `model <モデル名>` コマンドを実行したとき、THE ModelCommand SHALL 指定されたモデル名を現在のモデルとして設定する
2. WHEN モデルが変更されたとき、THE ModelCommand SHALL 変更後のモデル名を標準出力に表示する
3. WHEN モデルが変更されたとき、THE ModelHolderService SHALL 以降のすべての AI 呼び出しで変更後のモデルを使用する
4. THE ModelHolderService SHALL モデル名をスレッドセーフに保持する

---

### 要件 3: 利用可能なモデル一覧の表示

**ユーザーストーリー:** ユーザーとして、OpenAI 互換 API で利用可能なモデルの一覧を確認したい。そうすることで、切り替え可能なモデルを把握できる。

#### 受け入れ基準

1. WHEN ユーザーが `models` コマンドを実行したとき、THE ModelsCommand SHALL OpenAI 互換 API からモデル一覧を取得して表示する
2. THE ModelsCommand SHALL モデル一覧をアルファベット順で表示する
3. WHEN 現在選択中のモデルが一覧に含まれるとき、THE ModelsCommand SHALL そのモデルの先頭に `*` を付けて表示する

---

### 要件 4: OpenAI 互換 API からのモデル一覧取得

**ユーザーストーリー:** システムとして、OpenAI 互換 API の `/v1/models` エンドポイントからモデル一覧を取得したい。そうすることで、接続先の API で利用可能なモデルを動的に把握できる。

#### 受け入れ基準

1. THE OpenAiCompatibleModelsService SHALL `spring.ai.openai.base-url` プロパティで設定されたベース URL の `/v1/models` エンドポイントに HTTP GET リクエストを送信する
2. WHEN ベース URL が `/v1` で終わるとき、THE OpenAiCompatibleModelsService SHALL `/models` を付加してエンドポイント URL を構築する
3. WHEN ベース URL が `/v1` で終わらないとき、THE OpenAiCompatibleModelsService SHALL `/v1/models` を付加してエンドポイント URL を構築する
4. WHEN API キーが設定されているとき、THE OpenAiCompatibleModelsService SHALL `Authorization: Bearer <apiKey>` ヘッダーを付加してリクエストを送信する
5. WHEN HTTP レスポンスのステータスコードが 200 番台でないとき、THE OpenAiCompatibleModelsService SHALL エラーを送出する
6. THE OpenAiCompatibleModelsService SHALL レスポンスの `data` 配列から各モデルの `id` フィールドを抽出してリストを返す
7. WHEN ネットワークエラーが発生したとき、THE OpenAiCompatibleModelsService SHALL エラーを送出する
8. WHEN スレッドが割り込まれたとき、THE OpenAiCompatibleModelsService SHALL スレッドの割り込みフラグを復元してエラーを送出する
