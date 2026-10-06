# Spring Boot 4.1.1 / Spring AI 2.0.1 への更新

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
すでに sequence がある DB は再採番しません。新規 DB の table 作成は Spring AI の initializer が担当します。

アプリの更新前には通常どおり Rei data directory のバックアップを取ってください。

## 検証

標準の全検証コマンドは `./mvnw verify -Pintegration` です。ネットワークを必要とする依存取得と sqlite-vec 初回準備を除き、OpenAI 互換 API のテストはローカル fixture または in-memory SDK transport を使用します。

テスト環境で Mockito の動的 agent attach が利用できない場合は、Mockito の推奨する起動時 `-javaagent` 設定を Surefire の `argLine` に渡します。システム設定の変更は不要です。

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
