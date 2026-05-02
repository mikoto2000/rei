# 要件定義書: Config 機能

## はじめに

本機能は、AI エージェント「rei」に外部設定ファイルの管理機能を提供する。
ユーザーはコマンドラインから設定ファイルのパス確認とテンプレート生成を行い、OpenAI 互換 API の接続先・モデル・各種機能の設定を管理できる。

## 用語集

- **ConfigCommand**: 外部設定ファイルの操作コマンド（パス表示・初期化）。
- **ExternalConfigFileService**: 外部設定ファイルのパス解決・初期化を担うサービス。
- **ExternalConfigSupport**: アプリケーション起動時に外部設定ファイルのパスを Spring Boot に渡すサポートクラス。
- **ReiPaths**: アプリケーションが使用するファイルパスを解決するユーティリティ。
- **設定ファイル**: YAML 形式の外部設定ファイル。作業ディレクトリ配下の所定パスに配置する。

## 要件

### 要件 1: 設定ファイルパスの表示

**ユーザーストーリー:** ユーザーとして、設定ファイルのパスを確認したい。そうすることで、設定ファイルの場所を把握して直接編集できる。

#### 受け入れ基準

1. WHEN ユーザーが `config path` コマンドを実行したとき、THE ConfigCommand SHALL 設定ファイルの絶対パスを標準出力に表示する
2. THE ExternalConfigFileService SHALL 作業ディレクトリを基準として設定ファイルのパスを解決する

---

### 要件 2: 設定ファイルテンプレートの生成

**ユーザーストーリー:** ユーザーとして、設定ファイルのテンプレートを生成したい。そうすることで、初期設定を素早く行える。

#### 受け入れ基準

1. WHEN ユーザーが `config init` コマンドを実行したとき、THE ConfigCommand SHALL 設定ファイルのテンプレートを生成する
2. WHEN 設定ファイルが既に存在するとき、THE ExternalConfigFileService SHALL 既存ファイルを上書きせずにパスを返す
3. WHEN `--force` オプションが指定されたとき、THE ExternalConfigFileService SHALL 既存の設定ファイルを上書きしてテンプレートを生成する
4. WHEN テンプレートが生成されたとき、THE ConfigCommand SHALL 生成されたファイルのパスを標準出力に表示する
5. THE ExternalConfigFileService SHALL 設定ファイルの親ディレクトリが存在しない場合は自動的に作成する

---

### 要件 3: 設定ファイルテンプレートの内容

**ユーザーストーリー:** ユーザーとして、生成されたテンプレートに必要な設定項目が含まれていてほしい。そうすることで、設定項目を調べることなく設定を完了できる。

#### 受け入れ基準

1. THE ExternalConfigFileService SHALL テンプレートに OpenAI 互換 API のベース URL・API キー・チャットモデル・埋め込みモデルの設定を含める
2. THE ExternalConfigFileService SHALL テンプレートに Web 検索の有効フラグ・プロバイダー設定（DuckDuckGo・Brave）を含める
3. THE ExternalConfigFileService SHALL テンプレートに興味関心機能・スモールトーク機能・フィード機能・Google カレンダー機能の設定を含める
4. THE ExternalConfigFileService SHALL テンプレートの各設定値を環境変数で上書き可能な形式（`${ENV_VAR:デフォルト値}`）で記述する

---

### 要件 4: アプリケーション起動時の設定ファイル読み込み

**ユーザーストーリー:** システムとして、アプリケーション起動時に外部設定ファイルを自動的に読み込みたい。そうすることで、ユーザーが設定ファイルを配置するだけで設定が反映される。

#### 受け入れ基準

1. WHEN アプリケーションが起動するとき、THE ExternalConfigSupport SHALL 外部設定ファイルのパスを Spring Boot の追加設定ロケーションとして登録する
2. WHEN 外部設定ファイルが存在しないとき、THE ExternalConfigSupport SHALL エラーを発生させずにデフォルト設定で起動する
