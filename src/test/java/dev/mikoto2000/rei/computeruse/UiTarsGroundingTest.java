package dev.mikoto2000.rei.computeruse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

class UiTarsGroundingTest {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path diagnostics;

  @Test void parsesLoggedCoordinatesAndDoesNotGuessScale() {
    assertArrayEquals(new double[]{.281,.659},GroundingProtocol.UITARS.parse("(281,659)"));
    assertArrayEquals(new double[]{.345,.120},GroundingProtocol.UITARS.parse("(345,120)"));
    assertArrayEquals(new double[]{0,1},GroundingProtocol.UITARS.parse(" (0, 1000) "));
    assertArrayEquals(new double[]{.001,.001},GroundingProtocol.UITARS.parse("(1,1)"));
    for (String bad : List.of("[281,659]","(1001,0)","(-1,0)","(NaN,0)","(1,2,3)","(1,2) (3,4)","Action: click(1,2)"))
      assertThrows(InvalidComputerDecision.class,()->GroundingProtocol.UITARS.parse(bad));
    assertThrows(InvalidComputerDecision.class,()->GroundingProtocol.SHOWUI.parse("(281,659)"));
  }

  @Test void usesDedicatedPromptMapsPointAndSavesUiTarsDiagnostics() throws Exception {
    var screen = ComputerUseServiceTest.screen();
    var display = screen.displays().getFirst();
    var grounding = mock(ChatModel.class);
    when(grounding.call(any(Prompt.class))).thenReturn(SpringAiComputerVisionModelTest.response("(500,500)"),
        SpringAiComputerVisionModelTest.response("(281,659)"));
    var model = TestGroundingModels.create(o -> new ComputerAction.Click(
        new ComputerAction.Target(display.geometry().id(),1,1,"Post button"),.9,ComputerAction.Risk.LOW),
        grounding,OpenAiChatOptions::builder,()->false,GroundingProtocol.UITARS);
    var action = (ComputerAction.Click)model.decide(new ComputerObservation("goal",screen,List.of(),1,20,diagnostics));
    int width = display.image().getWidth(), height = display.image().getHeight();
    int left = width/2-width/2/2, top = height/2-height/2/2;
    assertEquals(left+(int)(.281*(width/2)),action.target().x());
    assertEquals(top+(int)(.659*(height/2)),action.target().y());
    assertEquals((left+.281*(width/2))/width,action.target().normalizedX());
    var request = org.mockito.ArgumentCaptor.forClass(Prompt.class);
    verify(grounding,times(2)).call(request.capture());
    assertTrue(request.getValue().getInstructions().getFirst().getText().startsWith("Output only the coordinate"));
    var options = (OpenAiChatOptions)request.getValue().getOptions();
    assertEquals(1.0,options.getFrequencyPenalty());
    assertNull(options.getResponseFormat());
    assertTrue(java.nio.file.Files.exists(diagnostics.resolve("step-001/uitars-input.png")));
    assertTrue(java.nio.file.Files.readString(diagnostics.resolve("step-001/uitars-refinement-response.json")).contains("(281,659)"));
    assertFalse(java.nio.file.Files.exists(diagnostics.resolve("step-001/showui-input.png")));
  }
}
