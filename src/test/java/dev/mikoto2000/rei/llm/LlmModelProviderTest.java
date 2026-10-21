package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;

/** 言語モデルモデル提供元の結果と状態の契約を検証するテスト。 */
class LlmModelProviderTest {
  /** 用途に応じたモデル利用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void correctionInheritsChatEndpointAndCanOverrideOnlyItsModelWithoutCaptureOrFallback() {
    var fallback = Mockito.mock(ChatModel.class);
    Mockito.when(fallback.getOptions())
        .thenReturn(
            org.springframework.ai.openai.OpenAiChatOptions.builder().model("default").build());
    var properties = new LlmProperties();
    var chat = new LlmProperties.Server();
    chat.setBaseUrl("https://chat.invalid/v1");
    chat.setApiKey("test-key");
    chat.setModel("chat-model");
    properties.getFeatures().put(LlmFeature.CHAT, chat);
    var correction = new LlmProperties.Server();
    correction.setModel("corrector");
    properties.getFeatures().put(LlmFeature.VOICE_CORRECTION, correction);
    var provider = new LlmModelProvider(fallback, properties);
    var options = provider.chatOptions(LlmFeature.VOICE_CORRECTION, null);
    assertThat(options.getBaseUrl()).isEqualTo("https://chat.invalid/v1");
    assertThat(options.getModel()).isEqualTo("corrector");
    assertThat(provider.voiceCorrectionChatModel())
        .isInstanceOf(org.springframework.ai.openai.OpenAiChatModel.class);
    correction.setBaseUrl("https://correction.invalid/v1");
    assertThat(
            new LlmModelProvider(fallback, properties)
                .chatOptions(LlmFeature.VOICE_CORRECTION, null)
                .getBaseUrl())
        .isEqualTo("https://correction.invalid/v1");
  }

  /** 機能出力予算について、正確に単一設定済みトークン引数を維持することを確認する。 */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void featureOutputBudgetKeepsExactlyOneConfiguredTokenParameterOnTheWire(
      boolean completionTokens) {
    var requests = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
    var defaults =
        org.springframework.ai.openai.OpenAiChatOptions.builder()
            .baseUrl("https://budget.invalid/v1")
            .apiKey("test")
            .model("configured-model")
            .maxRetries(0);
    if (completionTokens) defaults.maxCompletionTokens(4096);
    else defaults.maxTokens(4096);
    var defaultModel =
        org.springframework.ai.openai.OpenAiChatModel.builder()
            .options(defaults.build())
            .httpClientBuilderCustomizer(
                builder ->
                    builder.interceptor(
                        chain -> {
                          var body = new okio.Buffer();
                          chain.request().body().writeTo(body);
                          requests.add(
                              new com.fasterxml.jackson.databind.ObjectMapper()
                                  .readTree(body.readByteArray()));
                          return new okhttp3.Response.Builder()
                              .request(chain.request())
                              .protocol(okhttp3.Protocol.HTTP_1_1)
                              .code(200)
                              .message("OK")
                              .body(
                                  okhttp3.ResponseBody.create(
                                      "{\"id\":\"test\",\"object\":\"chat.completion\",\"created\":0,\"model\":\"configured-model\","
                                          + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"done\"}}]}",
                                      okhttp3.MediaType.get("application/json")))
                              .build();
                        }))
            .build();
    var properties = new LlmProperties();
    var feature = new LlmProperties.Server();
    feature.setMaxOutputTokens(128);
    properties.getFeatures().put(LlmFeature.OUTPUT_LIMIT_PLANNER, feature);
    var provider = new LlmModelProvider(defaultModel, properties);
    var options = provider.chatOptions(LlmFeature.OUTPUT_LIMIT_PLANNER, null);

    provider
        .chatModel(LlmFeature.OUTPUT_LIMIT_PLANNER)
        .call(new org.springframework.ai.chat.prompt.Prompt("plan", options));

    assertThat(requests).hasSize(1);
    assertThat(
            requests
                .getFirst()
                .path(completionTokens ? "max_completion_tokens" : "max_tokens")
                .asInt())
        .isEqualTo(128);
    assertThat(requests.getFirst().has(completionTokens ? "max_tokens" : "max_completion_tokens"))
        .isFalse();
    assertThat(requests.getFirst().path("model").asText()).isEqualTo("configured-model");
    assertThat(defaultModel.getOptions().getMaxCompletionTokens())
        .isEqualTo(completionTokens ? 4096 : null);
    assertThat(defaultModel.getOptions().getMaxTokens()).isEqualTo(completionTokens ? null : 4096);
  }

  /** 用途に応じたモデル利用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void optionsWithoutAnExplicitModelKeepConfiguredModelAndProviderDefaults() {
    var defaultModel = Mockito.mock(ChatModel.class);
    Mockito.when(defaultModel.getOptions())
        .thenReturn(
            org.springframework.ai.openai.OpenAiChatOptions.builder()
                .model("configured-local-model")
                .temperature(0.7)
                .topP(0.8)
                .build());
    var provider = new LlmModelProvider(defaultModel, new LlmProperties());

    var options = provider.chatOptions(LlmFeature.CHAT, null, true);

    assertThat(options.getModel()).isEqualTo("configured-local-model");
    assertThat(options.getTemperature()).isEqualTo(0.7);
    assertThat(options.getTopP()).isEqualTo(0.8);
    assertThat(options.getMaxTokens()).isEqualTo(8192);
    assertThat(options.getStreamOptions().includeUsage()).isTrue();
  }

  /** 用途に応じたモデル利用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void customServerWithoutModelKeepsConfiguredDefaultModel() {
    var defaultModel = Mockito.mock(ChatModel.class);
    Mockito.when(defaultModel.getOptions())
        .thenReturn(
            org.springframework.ai.openai.OpenAiChatOptions.builder()
                .model("configured-local-model")
                .build());
    var properties = new LlmProperties();
    var server = new LlmProperties.Server();
    server.setBaseUrl("http://vision.example.test");
    properties.getFeatures().put(LlmFeature.COMPUTER_USE, server);
    var provider = new LlmModelProvider(defaultModel, properties);

    assertThat(provider.chatOptions(LlmFeature.COMPUTER_USE, null).getModel())
        .isEqualTo("configured-local-model");
    assertThat(provider.computerUseChatModel().getOptions().getModel())
        .isEqualTo("configured-local-model");
  }

  /** 用途に応じたモデル利用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void activityDoesNotSendScreenshotsToFallbackServer() {
    var properties = new LlmProperties();
    var server = new LlmProperties.Server();
    server.setBaseUrl("http://vision.example.test");
    properties.getFeatures().put("activity", server);
    assertThat(
            new LlmModelProvider(Mockito.mock(ChatModel.class), properties).chatModel("activity"))
        .isInstanceOf(org.springframework.ai.openai.OpenAiChatModel.class);
  }

  /** 画面操作について、出力予算なし変更チャット予算を利用することを確認する。 */
  @Test
  void computerUseOutputBudgetDoesNotChangeChatBudget() {
    LlmProperties properties = new LlmProperties();
    properties.setMaxOutputTokens(16384);
    LlmProperties.Server server = new LlmProperties.Server();
    server.setMaxOutputTokens(512);
    properties.getFeatures().put(LlmFeature.COMPUTER_USE, server);
    var provider = new LlmModelProvider(Mockito.mock(ChatModel.class), properties);
    assertThat(provider.chatOptions(LlmFeature.COMPUTER_USE, "vision").getMaxTokens())
        .isEqualTo(512);
    assertThat(provider.chatOptions(LlmFeature.CHAT, "chat").getMaxTokens()).isEqualTo(16384);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> server.setMaxOutputTokens(0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 画面操作について、利用者指定サーバーなし共通形式への調整モデルを利用することを確認する。 */
  @Test
  void computerUseCustomServerDoesNotWrapModelWithFallback() {
    ChatModel defaultModel = Mockito.mock(ChatModel.class);
    LlmProperties properties = new LlmProperties();
    LlmProperties.Server server = new LlmProperties.Server();
    server.setBaseUrl("http://vision.example.test");
    server.setModel("vision-model");
    properties.getFeatures().put(LlmFeature.COMPUTER_USE, server);

    var model = new LlmModelProvider(defaultModel, properties).computerUseChatModel();

    assertThat(model).isInstanceOf(org.springframework.ai.openai.OpenAiChatModel.class);
    assertThat(model).isNotSameAs(defaultModel);
    Mockito.verify(defaultModel, Mockito.never())
        .call(Mockito.any(org.springframework.ai.chat.prompt.Prompt.class));
  }

  /** 機能接続一覧について、OS名前解決結果を利用することを確認する。 */
  @Test
  void featureConnectionsUseOsNameResolutionForLocalHosts() {
    var client =
        org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient.builder().build();
    try {
      assertThat(client.getOkHttpClient().dns()).isSameAs(okhttp3.Dns.SYSTEM);
    } finally {
      client.close();
    }
  }

  /** 既定応答生成に使うモデルを返すことを確認する。 */
  @Test
  void returnsDefaultChatModelWhenFeatureServerIsNotConfigured() {
    ChatModel defaultModel = Mockito.mock(ChatModel.class);
    LlmModelProvider provider = new LlmModelProvider(defaultModel, new LlmProperties());

    assertThat(provider.chatModel(LlmFeature.CHAT)).isSameAs(defaultModel);
    assertThat(provider.model(LlmFeature.CHAT, "default-model")).isEqualTo("default-model");
    assertThat(provider.chatOptions(LlmFeature.CHAT, "default-model").getMaxTokens())
        .isEqualTo(8192);
  }

  /** 開始AI互換応答生成に使うモデルを作成することを確認する。 */
  @Test
  void createsOpenAiCompatibleChatModelWhenFeatureServerIsConfigured() {
    ChatModel defaultModel = Mockito.mock(ChatModel.class);
    LlmProperties properties = new LlmProperties();
    LlmProperties.Server server = new LlmProperties.Server();
    server.setBaseUrl("http://feature.example.test");
    server.setApiKey("feature-key");
    server.setModel("feature-model");
    properties.getFeatures().put(LlmFeature.SEARCH, server);

    LlmModelProvider provider = new LlmModelProvider(defaultModel, properties);

    assertThat(provider.chatModel(LlmFeature.SEARCH)).isInstanceOf(FallbackChatModel.class);
    assertThat(provider.chatModel(LlmFeature.SEARCH))
        .isSameAs(provider.chatModel(LlmFeature.SEARCH));
    assertThat(provider.model(LlmFeature.SEARCH, "default-model")).isEqualTo("feature-model");
    assertThat(provider.chatOptions(LlmFeature.SEARCH, "default-model").getMaxTokens())
        .isEqualTo(8192);
  }

  /** 用途に応じたモデル利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void appliesConfiguredMaxOutputTokensToChatOptions() {
    ChatModel defaultModel = Mockito.mock(ChatModel.class);
    LlmProperties properties = new LlmProperties();
    properties.setMaxOutputTokens(2048);
    LlmModelProvider provider = new LlmModelProvider(defaultModel, properties);

    assertThat(provider.chatOptions(LlmFeature.CHAT, "default-model").getMaxTokens())
        .isEqualTo(2048);
  }

  /** 用途に応じたモデル利用で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void fallsBackToDefaultMaxOutputTokensWhenConfiguredValueIsInvalid() {
    ChatModel defaultModel = Mockito.mock(ChatModel.class);
    LlmProperties properties = new LlmProperties();
    properties.setMaxOutputTokens(0);
    LlmModelProvider provider = new LlmModelProvider(defaultModel, properties);

    assertThat(provider.chatOptions(LlmFeature.CHAT, "default-model").getMaxTokens())
        .isEqualTo(8192);
  }
}
