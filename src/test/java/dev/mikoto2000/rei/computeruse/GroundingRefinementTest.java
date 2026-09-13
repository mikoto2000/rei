package dev.mikoto2000.rei.computeruse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

class GroundingRefinementTest {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path diagnostics;
  private String point(GroundingProtocol p, boolean edge) {
    return p == GroundingProtocol.SHOWUI ? (edge ? "[1,1]" : "[0.5,0.5]") : (edge ? "(1000,1000)" : "(500,500)");
  }
  private ComputerAction click(ComputerObservation o) {
    return new ComputerAction.DoubleClick(new ComputerAction.Target(o.screenshot().displays().getFirst().geometry().id(),1,1,"button"),.9,ComputerAction.Risk.LOW);
  }
  @ParameterizedTest @EnumSource(GroundingProtocol.class)
  void edgeCropMapsBackAndKeepsSeparateDiagnostics(GroundingProtocol p) throws Exception {
    var model = mock(ChatModel.class);
    when(model.call(any(Prompt.class))).thenReturn(SpringAiComputerVisionModelTest.response(point(p,true)),
        SpringAiComputerVisionModelTest.response(point(p,false)));
    var screen = ComputerUseServiceTest.screen();
    var vision = TestGroundingModels.create(this::click,model,OpenAiChatOptions::builder,()->false,p);
    var result = (ComputerAction.DoubleClick)vision.decide(new ComputerObservation("goal",screen,List.of(),1,20,diagnostics));
    var source = screen.displays().getFirst().image();
    assertEquals(source.getWidth()-source.getWidth()/2+(source.getWidth()/2)/2,result.target().x());
    assertEquals(source.getHeight()-source.getHeight()/2+(source.getHeight()/2)/2,result.target().y());
    var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(diagnostics.resolve("step-001/"+p.id+"-refinement-request.json").toFile());
    assertEquals(source.getWidth()-source.getWidth()/2,json.get("cropLeft").asInt());
    assertEquals(source.getWidth()/2,json.get("cropWidth").asInt());
    assertTrue(java.nio.file.Files.exists(diagnostics.resolve("step-001/"+p.id+"-response.json")));
    assertTrue(java.nio.file.Files.exists(diagnostics.resolve("step-001/"+p.id+"-refinement-input.png")));
    verify(model,times(2)).call(any(Prompt.class));
  }
  @ParameterizedTest @EnumSource(GroundingProtocol.class)
  void refinementFailureNeverDispatchesCoarseCoordinates(GroundingProtocol p) {
    var model = mock(ChatModel.class);
    when(model.call(any(Prompt.class))).thenReturn(SpringAiComputerVisionModelTest.response(point(p,false)),
        SpringAiComputerVisionModelTest.response("invalid"));
    var vision = TestGroundingModels.create(this::click,model,OpenAiChatOptions::builder,()->false,p);
    var service = new ComputerUseService(ComputerUseServiceTest::screen,vision,(a,s)->fail("must not dispatch"),a->{},
        SafetyPolicy.lowRiskOnly(),()->false,e->{},2,2);
    assertEquals(ComputerUseResult.Status.MODEL_ERROR,service.run("goal").status());
    verify(model,times(2)).call(any(Prompt.class));
  }
  @ParameterizedTest @EnumSource(GroundingProtocol.class)
  void cancellationAfterOverviewPreventsRefinement(GroundingProtocol p) {
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    var model = mock(ChatModel.class);
    when(model.call(any(Prompt.class))).thenAnswer(call -> { cancelled.set(true); return SpringAiComputerVisionModelTest.response(point(p,false)); });
    var vision = TestGroundingModels.create(this::click,model,OpenAiChatOptions::builder,cancelled::get,p);
    assertThrows(java.util.concurrent.CancellationException.class,()->vision.decide(new ComputerObservation("goal",ComputerUseServiceTest.screen(),List.of(),1,20)));
    verify(model).call(any(Prompt.class));
  }
}
