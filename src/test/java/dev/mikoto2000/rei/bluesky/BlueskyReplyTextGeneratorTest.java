package dev.mikoto2000.rei.bluesky;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.client.ChatClient.StreamResponseSpec;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

import dev.mikoto2000.rei.core.service.ModelHolderService;
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
    when(requestSpec.stream()).thenReturn(streamSpec);
    when(streamSpec.content()).thenReturn(Flux.just("  返信", "本文  "));
    BlueskyReplyTextGenerator generator = new BlueskyReplyTextGenerator(chatClientProvider, modelHolderService);

    String result = generator.generate("alice.bsky.social", "投稿本文", List.of(
        new BlueskyReplyConversationRepository.ConversationMessage("user", "前回本文",
            OffsetDateTime.parse("2026-08-06T00:00:00Z"))));

    assertThat(result).isEqualTo("返信本文");
    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatClient).prompt(promptCaptor.capture());
    assertThat(promptCaptor.getValue().getOptions().getModel()).isEqualTo("qwen-test");
    assertThat(promptCaptor.getValue().getContents()).contains("投稿本文");
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
    when(requestSpec.stream()).thenReturn(streamSpec);
    when(streamSpec.content()).thenReturn(Flux.just("ありが", "とうございます"));
    BlueskyReplyTextGenerator generator = new BlueskyReplyTextGenerator(chatClientProvider, modelHolderService);

    String result = generator.generateForManualReply("元投稿");

    assertThat(result).isEqualTo("ありがとうございます");
    verify(chatClient).prompt(any(Prompt.class));
  }
}
