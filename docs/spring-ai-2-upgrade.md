# Spring Boot 4.1.1 / Spring AI 2.0.1 への更新

現在の基盤は Spring Boot 4.2.0 / Spring AI 2.1.0 です（2026-11-21 更新）。
以下の4.1.1 / 2.0.1 の説明は、その時点の移行記録です。

### 依存更新と警告対応の互換性レビュー（2026-11-21）

対象は依存更新 `c2ff9870` と警告対応 `984ee03a` です。

JDBC の互換実装が旧構成の `continue-on-error`、`platform` と、設定済み `JdbcTemplate` を
引き継いでいなかったため修正しました。Spring Boot の標準 schema initializer と property binding を使い、
旧 starter と同じ既定値（continue-on-error は true）、明示指定した false、platform 置換を維持します。
履歴のクエリには注入された `JdbcTemplate` を使い、query timeout などの設定も維持します。
設定メタデータも残し、既存のプロパティ名をそのまま利用できます。

旧実装で再現テストの失敗を確認し、修正後は起動・履歴・会話文脈・Shell を含む286件が成功しました。
Checkstyle / SpotBugs / Spotless は指摘0件です。
メモリの切り詰め・stream 集約・JDBC 保存処理は、更新前の Spring AI 2.0.2 の公式ソースとも照合しました。
書式変更を含む既存947 Java ファイルを javac で構文解析し、文字列値の列は941ファイルで完全一致、
残る6ファイルもキーマップ・設定・テストの意図した変更だけであることを確認しました。
プロンプト、SQL、表示文言に意図しない変更は見つかっていません。

Rust の HTTPS 信頼方式も更新前の Mozilla CA bundle に戻しました。
[クライアントの検証記録](../client/VALIDATION.md)を参照してください。

今回の更新では、直接指定している Java ライブラリと Maven プラグインも Maven Central の
最新安定版へ更新しました。軽量 CLI と Backend の JLine は 4.7.0 にそろえています。
JLine 4 がマウス用 terminfo を文字列 `\\E[M` のまま登録する場合に、Windows パスの
バックスラッシュ直後の文字が失われるため、共通の LineReader でキー定義を正規化します。
X10 / SGR と `\E` / octal 表記を端末の capability からデコードし、マウス操作と既存のキー定義を維持します。
文字入力・Tab 補完・複数行入力と軽量 CLI の回帰テストで確認しています。
JLine 4 では標準出力・標準エラーの文字コードも個別に解決されるため、JLine の明示的な
設定を尊重しつつ、Rei が使用する PrintStream の文字コードを両方へ指定します。
実ターミナルで UTF-8 / windows-31j の日本語出力を確認しています。

Spring AI のツール応答はコピーされるため、結果の保持を検証するテストはオブジェクトの
同一性ではなく値の一致を確認します。ArchUnit 1.5 の catch 節検出に伴う基準データの移行は
[アーキテクチャ資料](architecture/README.md)を参照してください。
google-java-format 2.0.0 の新しい書式は Java ソース全体へ適用しています。

永続 DB のスキーマ変更は追加していません。既存のストレージ移行テストも全件回帰の対象です。

### 警告への互換対応（2026-11-21）

非推奨の ChatMemory / MessageChatMemoryAdvisor / JDBC memory repository への参照を取り除き、
既存の bounded memory の契約を `dev.mikoto2000.rei.chatmemory` に保持しています。
Spring AI の新しい Session API は保存形式と保持規則が異なるため、その API への切り替えは行いません。
既存の `SPRING_AI_CHAT_MEMORY`、timestamp metadata、sequence、system message と turn 単位の
切り詰め、stream 集約を維持します。既存の JDBC schema 初期化プロパティと classpath も維持し、
非推奨の自動構成への依存を外しています。適用元の Apache 2.0 ライセンスと変更通知を同梱します。

Google HTTP transport は保守されている builder API を使い、JDK 証明書の優先・同梱証明書への
fallback・mTLS の選択を維持します。独立した transport パッケージに置き、業務間の循環依存を増やしません。
構成クラスから新しい memory 型への7本の明示的な接続のみ、アーキテクチャの基準へ追加しています。
アプリの共有メモリは、旧自動構成と同じ JDBC 保存・既定20メッセージで明示的に生成します。
JLine は columns / rows と Size.of、Jackson 3 は asString を使用します。

Maven とアプリ起動、テスト JVM はネイティブライブラリ利用を明示します。
Mockito は動的 attach ではなく起動時 agent を使用します。agent が bootstrap classpath を
変更するテスト JVM では CDS を使用しません。コンパイラは deprecation / unchecked を表示し、警告をビルド失敗にします。
警告のフィルター、`@SuppressWarnings`、Clippy の `allow` は追加していません。
異常系テストで仕様どおり出力する WARN ログと、環境条件によるテスト skip は維持します。

警告対応後の全件回帰は7,548件、failures / errors = 0、想定 skip = 12 です。
skip の ID と理由も既存の `config/test-expected-skips.json` と一致しています。
最後の生成境界・null 判定の整理後は、起動・履歴・会話文脈を含む169件を追加検証しました。
最終コードの Checkstyle / SpotBugs / Spotless は指摘0件です。
軽量 CLI 179件、Rust native 132件、Frontend 103件、ブラウザ E2E 38件も成功し、
Clippy の `-D warnings` と Tauri debug assets 埋込みビルドでも警告は出ていません。

2026-11-21、Windows / JDK 25 で Backend の full 分類7,540件と、最後の修正に関係する
結合テストを含む201件を検証しました。再検証後の Surefire XML 1,293ファイルの集計は
failures / errors = 0、環境条件による skip = 12 です。
軽量 CLI は179件、Rust は130件、Frontend は103件、Desktop / Mobile のブラウザ E2E は
38件が成功しました。[クライアントの移行・検証記録](../client/VALIDATION.md)も参照してください。
Checkstyle / SpotBugs / Spotless は指摘0件、Tauri の Windows debug 実行ファイルの
ビルドと厳格な Clippy 検査も成功しています。

Java 25 は維持し、Spring Boot を 4.0.4 から 4.1.1、Spring AI を 2.0.0-M3 から 2.0.1 に更新しました。
`spring-ai-advisors-vector-store` は正式版の `spring-ai-vector-store-advisor` に変更しています。

## 互換対応

- Spring AI の immutable options に合わせて、変更は `mutate()` から新しい request options を作成します。元の model、token budget、temperature などを維持し、呼び出し元の設定を変更しません。
- OpenAI 接続は Spring AI の旧 HTTP API から公式 OpenAI Java SDK に移行しています。既存の server-root 型 base URL は `/v1` に正規化し、すでに `/v1` を含む URL は重複させません。`REI_OPENAI_EMBEDDING_PATH` と embedding 専用接続設定も維持します。
- ShowUI 向けの image-before-text 変換は SDK の interceptor に接続しています。モデル名、出力上限、structured-output schema、usage の読み取りを wire-level fixture で検証します。
- fallback 時は、元の request のモデル名を fallback 側の設定に切り替えます。新しい options の暗黙のモデル名や timeout が、設定済みの値を上書きしないようにしています。
- ツールを使わない推論は callbacks と tool choice を明示的に制限します。SDK の `extraBody.tools` による別経路のツール指定も拒否します。
- `RunAwareToolCallingAdvisor` は実行中の Rei Run がある場合、アプリの `StagnationChatModel` にツール実行を委ねます。通常の非 Run 呼び出しは advisor 側でツール処理を継続します。キャンセル、権限確認、予算管理を別のツールループで迂回しません。
- アプリが管理するループでは、従来の LLM call budget、SubAgent の maxSteps、timeout を使います。Spring AI 2 の履歴依存の追加上限によって、ツールバッチの保存前に中断されないようにしています。
- 削除された `PromptChatMemoryAdvisor` は `MessageChatMemoryAdvisor` に変更し、Run のない従来の呼び出しの default conversation も維持しています。

## 既存 SQLite 履歴

Spring AI 2 の JDBC chat memory は `sequence_id` と対応する index を必要とします。
既存の `SPRING_AI_CHAT_MEMORY` が旧 schema の場合、Rei は Spring AI の schema initializer より先に、transaction 内で column と index を追加します。

履歴の content、type、timestamp は書き換えません。conversation ごとに timestamp、同時刻の場合は SQLite rowid の順で sequence を設定します。
すでに sequence がある DB は再採番しません。新規 DB の table 作成は Rei の互換 initializer が担当します。

アプリの更新前には通常どおり Rei data directory のバックアップを取ってください。

## 検証

標準の全検証コマンドは `./mvnw verify -Pintegration` です。ネットワークを必要とする依存取得と sqlite-vec 初回準備を除き、OpenAI 互換 API のテストはローカル fixture または in-memory SDK transport を使用します。

Mockito の推奨する起動時 `-javaagent` は Surefire の `argLine` に設定済みです。システム設定の変更は不要です。

## main 更新分の再確認（2026-10-07）

`c5f3d88af03b90a085e19aa40f59c74524d939f0` までの main を通常の merge で取り込みました。
[Spring Boot](https://spring.io/projects/spring-boot/) と [Spring AI](https://spring.io/projects/spring-ai/) の公式ページを再確認し、4.1.1 / 2.0.1、Java 25 を維持しています。

新しく追加された機能についても、次の互換境界を確認しています。

- Memory / Sleep の抽出呼び出しは immutable options を複製し、model・temperature・出力上限を維持したままツールを無効化します。
- SubAgent semantic validator は元の options を変更せず、callbacks と親の tool context を取り除き、validator の実行所有者だけを渡します。
- token budget 付き Agent Skill 選択は汎用 options ではなく OpenAI provider の options を保持します。SDK の HTTP request fixture で model、temperature、両 token parameter 系統、ツール禁止、usage の課金を確認します。
- SubAgent の opt-in retry は OpenAI SDK の transient error に対応します。部分応答を受けた後、キャンセル後、使用量不明時の再試行禁止と、共有予算を維持します。SDK 内部の retry 設定は変更しません。
- Context Budget の出力予約は `max_tokens` と `max_completion_tokens` の両方を考慮します。
- Embedding の SDK usage が Run の予算に反映され、上限到達後は次のリクエストを送らないことを in-memory transport で確認します。

Run / Goal / Sleep、rerank、MCP callback の登録、SQLite 履歴 migration / serialization、HTTP API、外部 Codex / Claude Code adapter も回帰確認の対象です。
外部モデルへの有料・実機呼び出し、ユーザーの既存 DB、実 MCP server、実 Codex / Claude Code CLI のログイン・外部接続は検証に使用していません。

### 再検証結果

Linux / Microsoft OpenJDK 25.0.4.1 / Maven Wrapper 3.9.14 で、最終コードに対して次を実行しました。

- `./mvnw verify -Pintegration`: **3,460 tests、3,459 passed、0 failures、0 errors、1 skipped**。実行可能な Spring Boot JAR の生成まで成功しました。
- skip は既存の Windows 専用 `JLineCompletionInputTest.tabCompletesWindowsPathAndPassesItToCd` です。
- 新しい options / retry / budget の重点テスト 116 件、および全体実行で見つかった fixture / SQLite-vector 関連 80 件の再実行も成功しています。これらは上記全体件数に含まれます。
- client: `npm test` **85 passed**、`npm run build`（TypeScript check を含む）、`npm run lint` 成功。
- cloud の Playwright は Chromium の Unix socket 作成が禁止され、28 ケースとも browser 起動段階で停止しました。UI assertion を通過した結果としては扱っていません。
- Rust の cloud 再実行は tooling 不在で未実施。client の追跡対象ソースは取り込み対象 main と同一で、この更新では変更していません。

Mockito は起動時 `-javaagent` を使用しました。依存取得には cloud の既存 proxy と system trust store を使用し、sqlite-vec はテスト用 cache に公式 manifest の checksum を検証して配置しました。ユーザーの設定や DB は使用・変更していません。

全体テストに含まれる主な境界は、Run / Goal の共有 call・token budget、Sleep / memory consolidation、SubAgent tool / evidence / semantic validation / repair / retry、embedding / rerank、MCP callback、SQLite 履歴の旧 schema 移行・読み書き、HTTP API、外部 Codex / Claude adapter の fixture です。実モデル・実 MCP server・認証済み CLI の外部接続と本番データでの確認は別途必要です。
