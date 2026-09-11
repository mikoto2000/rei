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

  @Test void applicationWiresWorkflowWithoutTouchingDesktopOrNetwork() throws Exception {
    when(capture.captureScreen()).thenReturn(ComputerUseServiceTest.screen());
    when(vision.decide(any())).thenReturn(new ComputerAction.Done("Visible"));
    assertEquals(ComputerUseResult.Status.DONE,tools.computerUse("goal").status());
    verify(capture).captureScreen();
    verifyNoInteractions(input,stabilizer);
  }
}
