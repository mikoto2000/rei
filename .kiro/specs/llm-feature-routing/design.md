# 設計書: 機能別 LLM ルーティング

## 概要

`rei.llm.features` 配下の設定を読み取り、LLM を必要とする機能ごとに `ChatModel` / `ChatClient` を切り替える。
機能別 `base-url` が未指定の場合は既存の Spring AI 既定 `ChatModel` を使用し、既存設定との互換性を維持する。

## 設定

```yaml
rei:
  llm:
    features:
      chat:
        base-url: ${REI_LLM_CHAT_BASE_URL:}
        api-key: ${REI_LLM_CHAT_API_KEY:}
        model: ${REI_LLM_CHAT_MODEL:}
      search:
        base-url: ${REI_LLM_SEARCH_BASE_URL:}
        api-key: ${REI_LLM_SEARCH_API_KEY:}
        model: ${REI_LLM_SEARCH_MODEL:}
```

`base-url` が空の場合、その機能は `spring.ai.openai` で構成された既定 LLM を使用する。

## コンポーネント

- **LlmProperties**
  - `rei.llm.features` を保持する ConfigurationProperties。
- **LlmFeature**
  - 機能キーの定数を定義する。
- **LlmModelProvider**
  - 機能キーごとに `ChatModel` を返す。
  - 機能別 `base-url` がある場合は OpenAI 互換 `OpenAiChatModel` を生成してキャッシュする。
  - 機能別 `model` がある場合はリクエストオプションのモデル名として返す。
- **LlmChatClientProvider**
  - 機能キーごとに `ChatClient` を返す。
  - Tool Bean は `ObjectProvider` で遅延取得し、機能サービスとの循環依存を避ける。

## 対象機能

- `ChatCommand`: `chat`
- `SearchCommand`: `search`
- `MemoryConsolidatorService`: `memory`
- `BlueskyReplyTextGenerator`: `bluesky-reply`
- `LlmFeedSummaryGenerator`: `feed-summary`
- `AiBriefingNarrator`: `briefing`
- `LlmInterestTopicExtractor`: `interest-discovery`
- `AgentSkillImplicitSelector`: `agent-skills`

## 後方互換

- 機能別 `base-url` が未設定なら既定 `ChatModel` を使う。
- 機能別 `model` が未設定なら既存の `ModelHolderService` または既定モデルを使う。
- 既存テスト向けの旧コンストラクタは維持し、Spring DI では Provider 経由のコンストラクタを使用する。

## テスト方針

- 機能別 `base-url` 未指定時に既定 `ChatModel` が返ること。
- 機能別 `base-url` 指定時に OpenAI 互換 `ChatModel` が生成され、同一機能ではキャッシュされること。
- 機能別 `model` 指定時に指定モデルが優先されること。
- Spring Context 起動時に `LlmChatClientProvider` と Tool 系 Bean の循環依存が発生しないこと。
