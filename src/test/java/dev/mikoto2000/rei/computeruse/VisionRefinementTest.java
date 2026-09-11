package dev.mikoto2000.rei.computeruse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

class VisionRefinementTest {
  static ChatResponse click(double x, double y, String risk) {
    return SpringAiComputerVisionModelTest.response(ActionValidationTest.json("CLICK","target",
        "{\"displayId\":\"left\",\"description\":\"input\",\"centerX\":"+x+",\"centerY\":"+y+"}")
        .replace("\"confidence\":null","\"confidence\":0.9").replace("\"LOW\"","\""+risk+"\""));
  }
  @Test void refinesOnlySelectedDisplayAndMapsCropBackWithoutReducingRisk() throws Exception {
    var model = mock(ChatModel.class);
    when(model.call(any(Prompt.class))).thenReturn(click(.5,.5,"CONFIRM_REQUIRED"),click(.75,.25,"LOW"));
    var action = (ComputerAction.Click)new SpringAiComputerVisionModel(model,OpenAiChatOptions::builder,()->false,0)
        .decide(new ComputerObservation("focus input",MultiDisplayTest.desktop(),List.of(),1,20));
    assertEquals(1000,action.target().x()); assertEquals(450,action.target().y());
    assertEquals(ComputerAction.Risk.CONFIRM_REQUIRED,action.risk());
    var prompts = org.mockito.ArgumentCaptor.forClass(Prompt.class);
    verify(model,times(2)).call(prompts.capture());
    var user = (org.springframework.ai.chat.messages.UserMessage)prompts.getAllValues().getLast().getInstructions().getLast();
    assertEquals(1,user.getMedia().size());
    assertTrue(user.getText().contains("REFINEMENT"));
  }
  @Test void neverFallsBackToCoarseClickWhenRefinementCannotFindTarget() throws Exception {
    var model = mock(ChatModel.class);
    when(model.call(any(Prompt.class))).thenReturn(click(.5,.5,"LOW"),
        SpringAiComputerVisionModelTest.response(ActionValidationTest.json("UNCERTAIN","reason","\"Not visible\"")));
    assertInstanceOf(ComputerAction.Uncertain.class,new SpringAiComputerVisionModel(model,OpenAiChatOptions::builder,()->false,0)
        .decide(new ComputerObservation("goal",MultiDisplayTest.desktop(),List.of(),1,20)));
  }
  @Test void cropCannotDeclareWholeGoalDoneAndRecordsItsBounds(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    var model = mock(ChatModel.class);
    when(model.call(any(Prompt.class))).thenReturn(click(.5,.5,"LOW"),
        SpringAiComputerVisionModelTest.response(ActionValidationTest.json("DONE","reason","\"visible\"")));
    assertInstanceOf(ComputerAction.Uncertain.class,new SpringAiComputerVisionModel(model,OpenAiChatOptions::builder,()->false,0)
        .decide(new ComputerObservation("goal",MultiDisplayTest.desktop(),List.of(),1,20,directory)));
    var crop = javax.imageio.ImageIO.read(directory.resolve("step-001/refinement.png").toFile());
    assertEquals(800,crop.getWidth()); assertEquals(600,crop.getHeight());
    var metadata = new com.fasterxml.jackson.databind.ObjectMapper().readTree(directory.resolve("step-001/refinement.json").toFile());
    assertEquals(400,metadata.get("left").asInt()); assertEquals(300,metadata.get("top").asInt());
  }
  @Test void cancellationAfterOverviewPreventsRefinement() {
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    var model = mock(ChatModel.class);
    when(model.call(any(Prompt.class))).thenAnswer(invocation -> { cancelled.set(true); return click(.5,.5,"LOW"); });
    assertThrows(java.util.concurrent.CancellationException.class, () ->
        new SpringAiComputerVisionModel(model,OpenAiChatOptions::builder,cancelled::get,0)
            .decide(new ComputerObservation("goal",MultiDisplayTest.desktop(),List.of(),1,20)));
    verify(model).call(any(Prompt.class));
  }
}
