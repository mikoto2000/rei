package dev.mikoto2000.rei.subagent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.working.WorkingSet;
import dev.mikoto2000.rei.llm.*;
import dev.mikoto2000.rei.vectordocument.VectorDocumentRepository;
import reactor.core.publisher.Flux;

@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class SubAgentIntegrationTest {
  @MockitoBean ChatModel model;
  @MockitoBean EmbeddingModel embedding;
  @MockitoBean VectorStore vectors;
  @MockitoBean VectorDocumentRepository documents;
  @Autowired SubAgentRunner runner;
  @Autowired SubAgentRegistry registry;
  @Autowired ChatMemory history;
  @Autowired WorkingSet workingSet;
  @Autowired LlmChatClientProvider clients;
  static Path directory;
  @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) throws Exception {
    directory = Files.createTempDirectory("rei-subagent-test-");
    properties.add("rei.subagents.directory", () -> directory.toString());
  }
  @Test void realCallbacksLeaveMainHistoryAndWorkingSetUntouchedAndParentSeesDelegation() throws Exception {
    Files.writeString(directory.resolve("reviewer.yaml"), SubAgentConfigurationTest.yaml("reviewer"));
    Files.writeString(directory.resolve("note.txt"), "independent evidence");
    assertThat(registry.reload()).isEmpty();
    String main = "test-parent-" + UUID.randomUUID();
    history.add(main, List.of(new UserMessage("parent private reasoning")));
    List<Prompt> requests = new CopyOnWriteArrayList<>();
    when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
      Prompt prompt = invocation.getArgument(0); requests.add(prompt);
      var message = requests.size() == 1 ? AssistantMessage.builder().content("").toolCalls(List.of(
          new AssistantMessage.ToolCall("read", "function", "readMultiFile", "{\"files\":[{\"path\":\"note.txt\"}]}"))).build()
          : new AssistantMessage("independent review");
      return Flux.just(new ChatResponse(List.of(new Generation(message))));
    });
    try (var scope = AgentRunScope.open(new AgentRunContext("parent", main, directory))) {
      var before = workingSet.getFiles();
      var result = runner.run("reviewer", "review note.txt", null);
      assertThat(result.status()).isEqualTo(SubAgentResult.Status.COMPLETED);
      assertThat(requests).allMatch(p -> !p.getContents().contains("parent private reasoning"));
      assertThat(requests.getLast().getInstructions()).filteredOn(m -> m instanceof ToolResponseMessage)
          .anyMatch(m -> ((ToolResponseMessage)m).getResponses().toString().contains("independent evidence"));
      assertThat(workingSet.getFiles()).isEqualTo(before);
      assertThat(history.get(main)).extracting(Message::getText).containsExactly("parent private reasoning");
      assertThat(history.get("subagent:" + result.subAgentRunId())).isEmpty();
    } finally { history.clear(main); }
    var captured = new java.util.concurrent.atomic.AtomicReference<Prompt>();
    when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
      captured.set(invocation.getArgument(0));
      return new ChatResponse(List.of(new Generation(new AssistantMessage("parent response"))));
    });
    try {
      clients.chatClient(LlmFeature.CHAT).prompt().user("review")
          .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, main)).call().content();
    } finally { history.clear(main); }
    var callbacks = ((ToolCallingChatOptions) captured.get().getOptions()).getToolCallbacks();
    assertThat(callbacks).anyMatch(c -> c.getToolDefinition().name().equals("delegateTask")
        && c.getToolDefinition().description().contains("reviewer")
        && !c.getToolDefinition().description().contains("Review independently."));
  }
}
