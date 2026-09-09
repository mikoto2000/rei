package dev.mikoto2000.rei.core.chat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.memory.*;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;
import reactor.core.publisher.Flux;
import static org.assertj.core.api.Assertions.*;

class RunScopedChatMemoryTest {
  @TempDir Path temp;

  @Test void persistsCompleteAnswerOnceWhenFinishChunkHasNullText() {
    checkStream(List.of(chunk("過去の"), chunk("話題です。"), finish(null)), "過去の話題です。");
  }
  @Test void persistsCompleteAnswerRatherThanOnlyTheLastChunk() {
    checkStream(List.of(chunk("first "), finish("last")), "first last");
  }
  @Test void persistsAnswerWhenProviderOmitsFinishReason() {
    checkStream(List.of(chunk("first "), chunk("last")), "first last");
  }

  private void checkStream(List<ChatResponse> chunks, String expected) {
    var ds = new SQLiteDataSource(); ds.setUrl("jdbc:sqlite:" + temp.resolve("memory.db"));
    var jdbc = new JdbcTemplate(ds);
    jdbc.execute("CREATE TABLE SPRING_AI_CHAT_MEMORY(conversation_id TEXT NOT NULL, content TEXT NOT NULL, type TEXT NOT NULL, timestamp INTEGER NOT NULL)");
    var repository = JdbcChatMemoryRepository.builder().dataSource(ds).build();
    var memory = MessageWindowChatMemory.builder().chatMemoryRepository(repository).maxMessages(20).build();
    var run = new AgentRunContext("run-a", "project:" + UUID.randomUUID() + ":chat:main", temp);
    String other = "project:" + UUID.randomUUID() + ":chat:main";
    memory.add(run.conversationId(), new AssistantMessage("earlier answer"));
    memory.add(other, new AssistantMessage("B history"));
    ChatModel model = new ChatModel() {
      public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
      public Flux<ChatResponse> stream(Prompt prompt) { return Flux.fromIterable(chunks); }
    };
    var client = ChatClient.builder(model)
        .defaultAdvisors(RunScopedAdvisor.wrap(List.of(PromptChatMemoryAdvisor.builder(memory).build())))
        .build();
    List<ChatResponse> delivered;
    try (var ignored = AgentRunScope.open(run)) {
      delivered = client.prompt("remember?").stream().chatResponse().collectList().block(Duration.ofSeconds(10));
    }
    assertThat(delivered).hasSize(chunks.size());
    assertThat(delivered.stream().map(r -> r.getResult().getOutput().getText()).filter(Objects::nonNull)
        .reduce("", String::concat)).isEqualTo(expected);
    assertThat(memory.get(run.conversationId())).extracting(Message::getText)
        .containsExactly("earlier answer", "remember?", expected);
    assertThat(memory.get(other)).extracting(Message::getText).containsExactly("B history");
    assertThat(AgentRunScope.current()).isNull();
  }
  private ChatResponse chunk(String text) {
    return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
  }
  private ChatResponse finish(String text) {
    return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build(),
        ChatGenerationMetadata.builder().finishReason("stop").build())));
  }
}
