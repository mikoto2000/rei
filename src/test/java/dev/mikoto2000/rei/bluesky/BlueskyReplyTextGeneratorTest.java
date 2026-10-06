package dev.mikoto2000.rei.bluesky;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import java.util.function.Consumer;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.AdvisorSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.client.ChatClient.StreamResponseSpec;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.llm.LlmChatClientProvider;
import dev.mikoto2000.rei.llm.LlmModelProvider;
import reactor.core.publisher.Flux;

class BlueskyReplyTextGeneratorTest {

  @Test
  void generateUsesChatClientWithCurrentModel() {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ObjectProvider<ChatClient> chatClientProvider = Mockito.mock(ObjectProvider.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class);
    StreamResponseSpec streamSpec = Mockito.mock(StreamResponseSpec.class);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    when(chatClientProvider.getObject()).thenReturn(chatClient);
    when(modelHolderService.get()).thenReturn("qwen-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
    when(requestSpec.stream()).thenReturn(streamSpec);
    when(streamSpec.chatResponse()).thenReturn(Flux.just(response("  返信"), response("本文  ")));
    BlueskyReplyTextGenerator generator = new BlueskyReplyTextGenerator(chatClientProvider, modelHolderService);

    String result = generator.generate("alice.bsky.social", "投稿本文", List.of(
        new BlueskyReplyConversationRepository.ConversationMessage("user", "前回本文",
            OffsetDateTime.parse("2026-08-06T00:00:00Z"))));

    assertThat(result).isEqualTo("返信本文");
    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatClient).prompt(promptCaptor.capture());
    assertThat(promptCaptor.getValue().getOptions().getModel()).isEqualTo("qwen-test");
    assertThat(promptCaptor.getValue().getContents()).contains("投稿本文", "Return only the reply text");
    var options = (org.springframework.ai.openai.OpenAiChatOptions) promptCaptor.getValue().getOptions();
    assertThat(options.getToolChoice()).isEqualTo("none");
    assertThat(options.getToolCallbacks()).isEmpty();
    assertThat(options.getExtraBody()).isNullOrEmpty();
  }

  @Test
  void replyOptionsPreserveConfiguredModelWithoutMutatingProviderCallbacks() {
    var client = Mockito.mock(ChatClient.class);
    var clients = Mockito.mock(LlmChatClientProvider.class);
    var models = Mockito.mock(LlmModelProvider.class);
    var holder = Mockito.mock(ModelHolderService.class);
    var request = Mockito.mock(ChatClientRequestSpec.class);
    var stream = Mockito.mock(StreamResponseSpec.class);
    var callback = org.springframework.ai.tool.function.FunctionToolCallback.builder("unexpected", (String input) -> input)
        .description("must not be exposed").inputType(String.class).build();
    var configured = org.springframework.ai.openai.OpenAiChatOptions.builder()
        .model("reply-model").temperature(0.4).maxTokens(123)
        .extraBody(java.util.Map.of("custom_parameter", 7))
        .toolCallbacks(List.of(callback)).toolChoice("required").build();
    when(holder.get()).thenReturn("current-model");
    when(models.chatOptions(dev.mikoto2000.rei.llm.LlmFeature.BLUESKY_REPLY, "current-model")).thenReturn(configured);
    when(clients.chatClient(dev.mikoto2000.rei.llm.LlmFeature.BLUESKY_REPLY)).thenReturn(client);
    when(client.prompt(any(Prompt.class))).thenReturn(request);
    when(request.advisors(any(Consumer.class))).thenReturn(request);
    when(request.stream()).thenReturn(stream);
    when(stream.chatResponse()).thenReturn(Flux.just(response("返信")));
    var generator = new BlueskyReplyTextGenerator(clients, holder, models, new BlueskyProperties());

    assertThat(generator.generate("alice.bsky.social", "本文", List.of())).isEqualTo("返信");

    var prompt = ArgumentCaptor.forClass(Prompt.class);
    verify(client).prompt(prompt.capture());
    var actual = (org.springframework.ai.openai.OpenAiChatOptions) prompt.getValue().getOptions();
    assertThat(actual).isNotSameAs(configured);
    assertThat(actual.getModel()).isEqualTo("reply-model");
    assertThat(actual.getTemperature()).isEqualTo(0.4);
    assertThat(actual.getMaxTokens()).isEqualTo(123);
    assertThat(actual.getExtraBody()).containsExactlyEntriesOf(java.util.Map.of("custom_parameter", 7));
    assertThat(actual.getToolCallbacks()).isEmpty();
    assertThat(actual.getToolChoice()).isEqualTo("none");
    assertThat(configured.getToolCallbacks()).containsExactly(callback);
    assertThat(configured.getToolChoice()).isEqualTo("required");
  }

  @Test
  void rawToolDefinitionsAreRejectedBeforeSendingThePrompt() {
    var clients = Mockito.mock(LlmChatClientProvider.class);
    var models = Mockito.mock(LlmModelProvider.class);
    var holder = Mockito.mock(ModelHolderService.class);
    when(holder.get()).thenReturn("current-model");
    when(models.chatOptions(dev.mikoto2000.rei.llm.LlmFeature.BLUESKY_REPLY, "current-model"))
        .thenReturn(org.springframework.ai.openai.OpenAiChatOptions.builder()
            .extraBody(java.util.Map.of("tools", List.of(java.util.Map.of("type", "function")))).build());
    var generator = new BlueskyReplyTextGenerator(clients, holder, models, new BlueskyProperties());

    assertThatThrownBy(() -> generator.generate("alice.bsky.social", "本文", List.of()))
        .isInstanceOf(IllegalArgumentException.class);

    Mockito.verifyNoInteractions(clients);
  }

  @Test
  void generateSetsBlueskyReplyConversationId() {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ObjectProvider<ChatClient> chatClientProvider = Mockito.mock(ObjectProvider.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    StreamResponseSpec streamSpec = Mockito.mock(StreamResponseSpec.class);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    when(chatClientProvider.getObject()).thenReturn(chatClient);
    when(modelHolderService.get()).thenReturn("qwen-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
    when(requestSpec.stream()).thenReturn(streamSpec);
    when(streamSpec.chatResponse()).thenReturn(Flux.just(response("返信")));
    BlueskyReplyTextGenerator generator = new BlueskyReplyTextGenerator(chatClientProvider, modelHolderService);

    generator.generate("alice.bsky.social", "投稿本文", List.of());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Consumer<AdvisorSpec>> consumerCaptor = (ArgumentCaptor<Consumer<AdvisorSpec>>) (ArgumentCaptor<?>) ArgumentCaptor
        .forClass(Consumer.class);
    verify(requestSpec).advisors(consumerCaptor.capture());
    AdvisorSpec spec = Mockito.mock(AdvisorSpec.class);
    consumerCaptor.getValue().accept(spec);
    verify(spec).param(ChatMemory.CONVERSATION_ID, "bluesky-reply:alice.bsky.social");
  }

  @Test
  void generateForManualReplySetsBlueskyManualConversationId() {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ObjectProvider<ChatClient> chatClientProvider = Mockito.mock(ObjectProvider.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    StreamResponseSpec streamSpec = Mockito.mock(StreamResponseSpec.class);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    when(chatClientProvider.getObject()).thenReturn(chatClient);
    when(modelHolderService.get()).thenReturn("qwen-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
    when(requestSpec.stream()).thenReturn(streamSpec);
    when(streamSpec.chatResponse()).thenReturn(Flux.just(response("返信")));
    BlueskyReplyTextGenerator generator = new BlueskyReplyTextGenerator(chatClientProvider, modelHolderService);

    generator.generateForManualReply("元投稿", "at://did:plc:xxx/app.bsky.feed.post/abc");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Consumer<AdvisorSpec>> consumerCaptor = (ArgumentCaptor<Consumer<AdvisorSpec>>) (ArgumentCaptor<?>) ArgumentCaptor
        .forClass(Consumer.class);
    verify(requestSpec).advisors(consumerCaptor.capture());
    AdvisorSpec spec = Mockito.mock(AdvisorSpec.class);
    consumerCaptor.getValue().accept(spec);
    verify(spec).param(ChatMemory.CONVERSATION_ID,
        "bluesky-manual:at://did:plc:xxx/app.bsky.feed.post/abc");
  }

  @Test
  void generateForManualReplyUsesChatClient() {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ObjectProvider<ChatClient> chatClientProvider = Mockito.mock(ObjectProvider.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class);
    StreamResponseSpec streamSpec = Mockito.mock(StreamResponseSpec.class);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    when(chatClientProvider.getObject()).thenReturn(chatClient);
    when(modelHolderService.get()).thenReturn("qwen-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
    when(requestSpec.stream()).thenReturn(streamSpec);
    when(streamSpec.chatResponse()).thenReturn(Flux.just(response("ありが"), response("とうございます")));
    BlueskyReplyTextGenerator generator = new BlueskyReplyTextGenerator(chatClientProvider, modelHolderService);

    String result = generator.generateForManualReply("元投稿");

    assertThat(result).isEqualTo("ありがとうございます");
    verify(chatClient).prompt(any(Prompt.class));
  }

  @Test
  void generateThrowsWhenReplyContentIsBlank() {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ObjectProvider<ChatClient> chatClientProvider = Mockito.mock(ObjectProvider.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class);
    StreamResponseSpec streamSpec = Mockito.mock(StreamResponseSpec.class);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    when(chatClientProvider.getObject()).thenReturn(chatClient);
    when(modelHolderService.get()).thenReturn("qwen-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
    when(requestSpec.stream()).thenReturn(streamSpec);
    when(streamSpec.chatResponse()).thenReturn(Flux.just(response(" "), response("")));
    BlueskyReplyTextGenerator generator = new BlueskyReplyTextGenerator(chatClientProvider, modelHolderService);

    assertThatThrownBy(() -> generator.generate("alice.bsky.social", "投稿本文", List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("blank content");
  }

  @Test
  void generateThrowsWhenTargetPostTextIsBlank() {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ObjectProvider<ChatClient> chatClientProvider = Mockito.mock(ObjectProvider.class);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    when(chatClientProvider.getObject()).thenReturn(chatClient);
    BlueskyReplyTextGenerator generator = new BlueskyReplyTextGenerator(chatClientProvider, modelHolderService);

    assertThatThrownBy(() -> generator.generate("alice.bsky.social", " ", List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("target post text is blank");
  }

  @Test
  void generationTimeoutUsesConfiguredSeconds() {
    BlueskyProperties properties = new BlueskyProperties();
    properties.getReply().setGenerationTimeoutSeconds(5);
    BlueskyReplyTextGenerator generator = new BlueskyReplyTextGenerator(
        Mockito.mock(LlmChatClientProvider.class),
        Mockito.mock(ModelHolderService.class),
        Mockito.mock(LlmModelProvider.class),
        properties);

    assertThat(generator.generationTimeout()).isEqualTo(Duration.ofSeconds(5));
  }

  private static ChatResponse response(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "  返信本文  ",
      "<think>Let me count: roughly 70 characters. Good.</think>返信本文",
      "返信本文\n\nLet me count: roughly 70 characters. Good.</think>返信本文",
      "<think>first draft</think>draft<think>reconsider</think>返信本文",
      "<THINK>reasoning\nmore reasoning</THINK > 返信本文"
  })
  void removesThinkingAcrossStreamChunksForAllReplyPaths(String content) {
    BlueskyReplyTextGenerator generator = generatorStreaming(content);

    assertThat(generator.generate("alice.bsky.social", "元投稿", List.of())).isEqualTo("返信本文");
    assertThat(generator.generateForManualReply("元投稿")).isEqualTo("返信本文");
    assertThat(generator.generateForManualReply("元投稿", "post-id")).isEqualTo("返信本文");
    assertThat(generator.generateForManualReply("元投稿", "alice.bsky.social", "post-id"))
        .isEqualTo("返信本文");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "<think>unfinished reasoning",
      "draft<think>unfinished reasoning",
      "<think>reasoning</think>",
      "reasoning</think>  ",
      "<think>reasoning</think>draft<think>reconsider",
      "reasoning</think",
      "<think>"
  })
  void rejectsRepliesWithoutASafeAnswerForAllReplyPaths(String content) {
    BlueskyReplyTextGenerator generator = generatorStreaming(content);

    assertThatThrownBy(() -> generator.generate("alice.bsky.social", "元投稿", List.of()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> generator.generateForManualReply("元投稿"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> generator.generateForManualReply("元投稿", "post-id"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> generator.generateForManualReply("元投稿", "alice.bsky.social", "post-id"))
        .isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "webSearchAndRead({\"query\": \"dots LLM モデル 触り方 使い方\", \"maxResults\": 5})",
      "shell({\"command\":\"pwd\"})",
      "readFile({\"path\":\"README.md\"})",
      "webSearchAndRead({\n  \"query\": \"dots LLM\",\n  \"maxResults\": 5\n})",
      "  webSearchAndRead({\"query\":\"dots\"});  ",
      "someFutureTool({\"nested\":{\"values\":[1,2]}})",
      "$agent.tools.read-file ( { \"path\": \"README.md\" } ) ;",
      "<think>検索する必要がある</think>webSearchAndRead({\"query\":\"dots\"})"
  })
  void rejectsToolInvocationTextAcrossChunksForAllReplyPaths(String content) {
    // Plain assistant content carries no structured tool calls.
    assertThat(response(content).hasToolCalls()).isFalse();
    BlueskyReplyTextGenerator generator = generatorStreaming(content);

    assertThatThrownBy(() -> generator.generate("alice.bsky.social", "元投稿", List.of()))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("tool invocation text");
    assertThatThrownBy(() -> generator.generateForManualReply("元投稿"))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("tool invocation text");
    assertThatThrownBy(() -> generator.generateForManualReply("元投稿", "post-id"))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("tool invocation text");
    assertThatThrownBy(() -> generator.generateForManualReply("元投稿", "alice.bsky.social", "post-id"))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("tool invocation text");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "まず公式READMEを見て、最小構成で推論を1回動かしてみるのがよさそうです。",
      "設定は {\"model\":\"dots\"} みたいな形になると思います。",
      "内部的には foo({\"bar\":1}) のような形式ですね。",
      "関数で言えば foo(bar) みたいな感じですね",
      "<think>shell({\"command\":\"pwd\"})</think>返信本文"
  })
  void allowsNaturalLanguageForAllReplyPaths(String content) {
    BlueskyReplyTextGenerator generator = generatorStreaming(content);
    String expected = content.contains("</think>") ? "返信本文" : content;

    assertThat(generator.generate("alice.bsky.social", "元投稿", List.of())).isEqualTo(expected);
    assertThat(generator.generateForManualReply("元投稿")).isEqualTo(expected);
    assertThat(generator.generateForManualReply("元投稿", "post-id")).isEqualTo(expected);
    assertThat(generator.generateForManualReply("元投稿", "alice.bsky.social", "post-id"))
        .isEqualTo(expected);
  }

  @Test
  void rejectsStructuredToolCallsForAllReplyPaths() {
    ChatResponse toolResponse = new ChatResponse(List.of(new Generation(
        AssistantMessage.builder().content("返信本文").toolCalls(List.of(
            new AssistantMessage.ToolCall("call-1", "function", "shell", "{\"command\":\"pwd\"}")))
            .build())));
    assertThat(toolResponse.hasToolCalls()).isTrue();
    BlueskyReplyTextGenerator generator = generatorStreaming(Flux.just(response("返信"), toolResponse));

    assertThatThrownBy(() -> generator.generate("alice.bsky.social", "元投稿", List.of()))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("returned a tool call");
    assertThatThrownBy(() -> generator.generateForManualReply("元投稿"))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("returned a tool call");
    assertThatThrownBy(() -> generator.generateForManualReply("元投稿", "post-id"))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("returned a tool call");
    assertThatThrownBy(() -> generator.generateForManualReply("元投稿", "alice.bsky.social", "post-id"))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("returned a tool call");
  }

  private BlueskyReplyTextGenerator generatorStreaming(String content) {
    // One character per chunk exercises every possible tag and invocation boundary.
    return generatorStreaming(Flux.fromStream(
        () -> content.chars().mapToObj(c -> response(String.valueOf((char) c)))));
  }

  private BlueskyReplyTextGenerator generatorStreaming(Flux<ChatResponse> responses) {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ObjectProvider<ChatClient> chatClientProvider = Mockito.mock(ObjectProvider.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class);
    StreamResponseSpec streamSpec = Mockito.mock(StreamResponseSpec.class);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    when(chatClientProvider.getObject()).thenReturn(chatClient);
    when(modelHolderService.get()).thenReturn("qwen-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
    when(requestSpec.stream()).thenReturn(streamSpec);
    when(streamSpec.chatResponse()).thenReturn(responses);
    return new BlueskyReplyTextGenerator(chatClientProvider, modelHolderService);
  }
}
