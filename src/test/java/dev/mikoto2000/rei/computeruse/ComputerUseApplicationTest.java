package dev.mikoto2000.rei.computeruse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import dev.mikoto2000.rei.vectordocument.VectorDocumentRepository;

@SpringBootTest(properties = {"spring.ai.openai.api-key=test-key", "rei.computer-use.enabled=true"})
class ComputerUseApplicationTest {
  // ProjectService installs a legacy global adapter on context startup. Do not leak it to unit tests.
  private static final Object PREVIOUS_PROJECT_SERVICE = org.springframework.test.util.ReflectionTestUtils.getField(
      dev.mikoto2000.rei.core.project.ProjectService.class, "currentService");
  @org.junit.jupiter.api.AfterAll static void restoreProjectAdapter() {
    org.springframework.test.util.ReflectionTestUtils.setField(
        dev.mikoto2000.rei.core.project.ProjectService.class, "currentService", PREVIOUS_PROJECT_SERVICE);
  }
  @MockitoBean ChatModel chatModel;
  @MockitoBean EmbeddingModel embeddingModel;
  @MockitoBean VectorStore vectorStore;
  @MockitoBean VectorDocumentRepository repository;
  @MockitoBean ScreenCapture capture;
  @MockitoBean ComputerVisionModel vision;
  @MockitoBean ComputerInput input;
  @MockitoBean UiStabilizer stabilizer;
  @Autowired ComputerUseTools tools;
  @Autowired dev.mikoto2000.rei.llm.LlmChatClientProvider clients;
  @Autowired org.springframework.ai.chat.memory.ChatMemory history;

  @Test void actualChatRequestAdvertisesComputerUseExactlyOnceAndOtherFeaturesDoNot() {
    var captured = new java.util.concurrent.atomic.AtomicReference<org.springframework.ai.chat.prompt.Prompt>();
    when(chatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenAnswer(invocation -> {
      captured.set(invocation.getArgument(0));
      return reactor.core.publisher.Flux.just(new org.springframework.ai.chat.model.ChatResponse(java.util.List.of(
          new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage("Observed tool definitions")))));
    });
    for (String feature : java.util.List.of(dev.mikoto2000.rei.llm.LlmFeature.CHAT,
        dev.mikoto2000.rei.llm.LlmFeature.SEARCH, dev.mikoto2000.rei.llm.LlmFeature.MEMORY)) {
      String conversation = "computer-tool-advertisement-" + java.util.UUID.randomUUID();
      try {
        clients.chatClient(feature).prompt(new org.springframework.ai.chat.prompt.Prompt(
            new org.springframework.ai.chat.messages.UserMessage("List available tools"),
            org.springframework.ai.openai.OpenAiChatOptions.builder().model("test-model").build()))
            .advisors(a -> a.param(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID, conversation))
            .stream().chatResponse().collectList().block();
        var options = (org.springframework.ai.model.tool.ToolCallingChatOptions) captured.get().getOptions();
        var names = options.getToolCallbacks().stream().map(c -> c.getToolDefinition().name()).toList();
        assertEquals(feature.equals(dev.mikoto2000.rei.llm.LlmFeature.CHAT) ? 1 : 0,
            names.stream().filter("computerUse"::equals).count(), feature + " request must advertise the correct tools");
        assertFalse(names.contains("captureScreen"));
        assertFalse(names.contains("click"));
        assertFalse(names.contains("typeText"));
        String system = captured.get().getInstructions().stream()
            .filter(m -> m instanceof org.springframework.ai.chat.messages.SystemMessage)
            .map(m -> m.getText()).collect(java.util.stream.Collectors.joining("\n"));
        assertEquals(feature.equals(dev.mikoto2000.rei.llm.LlmFeature.CHAT),
            system.contains("Desktop task orchestration"));
        if (feature.equals(dev.mikoto2000.rei.llm.LlmFeature.CHAT)) assertTrue(names.contains("runCommand"));
      } finally { history.clear(conversation); }
    }
    verifyNoInteractions(capture, vision, input, stabilizer);
  }

  @Test void applicationWiresWorkflowWithoutTouchingDesktopOrNetwork() throws Exception {
    when(capture.captureScreen()).thenReturn(ComputerUseServiceTest.screen());
    when(vision.decide(any())).thenReturn(new ComputerAction.Done("Visible"));
    assertEquals(ComputerUseResult.Status.DONE,tools.computerUse("goal").status());
    verify(capture).captureScreen();
    verifyNoInteractions(input,stabilizer);
  }
}
