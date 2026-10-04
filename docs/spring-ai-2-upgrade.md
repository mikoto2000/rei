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
