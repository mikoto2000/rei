# Getting Started

Rei を初めて動かす人向けの最短手順ガイドです。詳細な機能説明は [README.md](README.md)、開発者向けの手順は [DEVELOP.md](DEVELOP.md) を参照してください。

## これは何？

Rei はターミナルで使う AI 秘書シェルです。OpenAI 互換 API を使った対話を中心に、調査、文書検索、予定・タスク管理などを一つの CLI で行えます。

- 基盤: Spring Boot 4.1.1 / Spring AI 2.0.1 / Java 25
- AI の処理には設定した OpenAI 互換 API サーバーを利用します（ローカルのモデルサーバーにも接続できます）
- 主な機能: AI との対話、画像の添付・生成、論文検索・要約、Web 検索、URL 要約、文書検索、Google Calendar / Tasks、RSS/Atom 購読、日次ブリーフィング、リマインド、MCP による外部ツール連携、SubAgent への委譲、Windows の画面操作

## 前提条件

- JDK 25 以上（`java -version` で確認できます）
- このリポジトリのソース一式
- 利用する OpenAI 互換 API の接続先、モデル名、必要に応じた API キー
- Web 検索や Google Calendar などを使う場合は、対応する認証情報（初回は不要です）

Maven は同梱の Maven Wrapper を使用します。初回実行時に依存ファイルをダウンロードするため、ネットワーク接続が必要です。

## 1. リポジトリを取得する

```bash
git clone <このリポジトリのURL>
cd rei
```

Dev Container（VS Code / GitHub Codespaces）を使う場合は、`.devcontainer/` の定義（Java 25、`ja_JP.UTF-8`、`Asia/Tokyo`）でそのまま開発を始められます。

## 2. AI の接続先を設定する

Rei は起動時に、共通データディレクトリ配下の `application.yaml` を自動で読み込みます。ターミナルごとの環境変数設定は不要で、ファイルに書いた設定は再起動後も引き継がれます。

保存先（`REI_DATA_DIR` 環境変数で変更できます）:

| OS | `application.yaml` の場所 |
| --- | --- |
| Windows | `%LOCALAPPDATA%\Rei\application.yaml` |
| Linux | `$XDG_DATA_HOME/rei/application.yaml`（未設定時は `~/.local/share/rei/application.yaml`） |
| macOS | `~/Library/Application Support/Rei/application.yaml` |

次の内容でファイルを作成します。この例は、同じ PC の `http://localhost:11434` で公開されている `qwen3.5:9b` に接続します。URL・モデル名・キーは実際の接続先に置き換えてください。キーが不要なローカルサーバーでは `dummy-key` を指定できます。

```yaml
spring:
  ai:
    openai:
      base-url: http://localhost:11434
      api-key: dummy-key
      chat:
        options:
          model: qwen3.5:9b

rei:
  embedding:
    enabled: false
```

まずは対話を始められるよう、文書の埋め込みを無効にしています。文書検索を使うときは `spring.ai.openai.embedding.options.model` に埋め込みモデルを設定し、`rei.embedding.enabled: true` にして再起動してください。画像生成には画像生成 API に対応する接続先とモデルが必要です。

ファイルの場所が分からなくなったら、起動後に `/config path` で確認できます。テンプレートの作成は `/config init` です。環境変数は外部設定ファイルより優先されるため、一時的な上書きに使えます。詳しくは [設定ガイド](docs/configuration.md) を参照してください。

## 3. ビルドする

リポジトリのルートで Maven Wrapper を実行し、実行可能な jar を作成します。

```bash
./mvnw package -DskipTests
```

Windows（PowerShell）では `.\mvnw.cmd package -DskipTests` です。ビルドが成功すると `target/rei-0.0.1-SNAPSHOT.jar` が生成されます。`-DskipTests` を外すと全テストも実行します（時間がかかるため、初回はスキップを推奨します）。

## 4. 起動する

生成した jar を起動します。JVM オプション付きの同梱スクリプトの利用を推奨します。

Linux / macOS / Git Bash:

```bash
sh ./start.sh
```

Windows（PowerShell）:

```powershell
.\start.bat
```

jar を直接起動することもできます。

```bash
java -jar target/rei-0.0.1-SNAPSHOT.jar
```

作業するプロジェクトのディレクトリを指定するには `--project` を渡します。引数はそのまま Rei に渡ります。

```bash
sh ./start.sh --project ../other-project
java -jar target/rei-0.0.1-SNAPSHOT.jar --project ../other-project
```

```powershell
.\start.bat --project F:\project\rei
```

相対パスは起動時のカレントディレクトリが基準です。存在しないパスを指定すると、ディレクトリを作成せずエラー終了します。起動オプションは `--help` 引数で確認できます（例: `sh ./start.sh --help`）。

起動スクリプトは `JAVA_HOME` が設定されていればその JDK を、未設定なら PATH 上の `java` を使用し、`--enable-native-access=ALL-UNNAMED` などの JVM オプション付きで jar を起動します。

## 5. 話しかける

Rei の入力プロンプトが表示されたら、文章をそのまま入力します。

```text
こんにちは
今日取り組むことを一緒に整理して
```

操作コマンドは `/` で始めます。`/help` でヘルプを表示し、`/exit` で終了できます。

## よく使うコマンド

| やりたいこと | 入力例 |
| --- | --- |
| 現在のモデルを確認・変更する | `/model` / `/model <モデル名>` |
| 調べる | `/search 調べたいこと` |
| Web ページを要約する | `/summarize https://example.com/article` |
| 画像を生成する | `/image "夕暮れの港の水彩画"` |
| 会話履歴を表示・検索する | `/history` / `/history search 検索したい言葉` |
| 新しい会話を開始する | `/session new [title]` |
| 作業フォルダーを切り替える | `/project cd "C:\work\my-project"` |
| 実行中の処理を確認する | `/runs` |
| 選択中プロジェクトの AI 実行を中止する | `/cancel` |
| 設定ファイルのパス確認・テンプレート作成 | `/config path` / `/config init` |
| ヘルプ・終了 | `/help` / `/exit` |

## テストを実行する

```bash
./mvnw test -q                      # 日常用の Unit / 軽量 Component テスト
./mvnw verify -Pintegration         # SQLite・filesystem・Spring Context・HTTP・実プロセスを含む全テスト
./mvnw test -Pfull                  # テストのみ全実行
./mvnw test -Pintegration-only      # Integration / System のみ
```

変更を完了する前に全テストを実行してください。分類・計測結果は [テスト性能レポート](docs/test-performance.md) を参照してください。CI では GitHub Actions（`windows-latest`、Temurin 25）で `\.github\ci\full-test.ps1` が実行されます。

## データと設定の保存先

設定や会話履歴などは、作業フォルダーとは別の共通ディレクトリに保存されます。既定の保存先は「2. AI の接続先を設定する」の表を参照してください。環境変数 `REI_DATA_DIR` で保存先を変更できます。

設定は環境変数、外部設定ファイル（`application.yaml`）、組み込み設定の順に優先されます。詳しくは [設定ガイド](docs/configuration.md) を参照してください。

## トラブルシューティング（最小限）

| 症状 | 確認すること |
| --- | --- |
| 起動できない | `java -version` が 25 以上か、リポジトリのルートで実行しているか。起動スクリプトを使う場合は `JAVA_HOME` が JDK 25 以上を指しているか |
| AI に接続できない、`401 Unauthorized` | 接続先が稼働しているか、`application.yaml` の `spring.ai.openai.base-url` と `spring.ai.openai.api-key` が正しいか |
| モデルが見つからない | 接続先が提供するモデル名を `spring.ai.openai.chat.options.model` に指定しているか |
| Web 検索が使えない | `rei.web-search.enabled` と検索サービスの設定（Brave には API キーが必要） |
| 絵文字で表示が乱れる | 起動時に `--notmux` を指定する |

さらに詳しい対処は [README の「困ったときは」](README.md#困ったときは) を参照してください。

## 次に読むドキュメント

| 読みたい内容 | ドキュメント |
| --- | --- |
| 全機能の使い方 | [docs/usage.md](docs/usage.md) |
| 設定（Google Calendar、Web 検索、MCP など） | [docs/configuration.md](docs/configuration.md) |
| 開発・テストの手順 | [DEVELOP.md](DEVELOP.md) |
| プログラム構造（新規開発者向け図解） | [docs/program-structure.md](docs/program-structure.md) |
| Tauri 2 ネイティブクライアント | [client/README.md](client/README.md) |
| 仕様・設計 | [.kiro/README.md](.kiro/README.md) |
