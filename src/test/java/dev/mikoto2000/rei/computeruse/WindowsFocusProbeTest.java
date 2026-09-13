package dev.mikoto2000.rei.computeruse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

class WindowsFocusProbeTest {
  static final String FOCUS = """
      {"status":"ok","name":"Compose","controlType":"ControlType.Edit","hasKeyboardFocus":true,
       "enabled":true,"password":false,"editable":true,"bounds":{"x":-3000,"y":100,"width":400,"height":100}}
      """;
  @Test void unknownIsNotEditableAndNegativeDisplayBoundsAreValid() throws Exception {
    assertTrue(WindowsFocusProbe.validate("{}").contains("invalid_snapshot"));
    assertTrue(WindowsFocusProbe.validate("{\"status\":\"ok\"}").contains("hasKeyboardFocus"));
    assertTrue(WindowsFocusProbe.validate(FOCUS).contains("-3000"));
    assertTrue(WindowsFocusProbe.validate(FOCUS.replace("\"editable\":true","\"editable\":null")).contains("\"editable\":null"));
  }
  @Test void preservesFailuresAndDoesNotTreatProbeWindowAsTarget() throws Exception {
    String failure="{\"status\":\"unknown\",\"reason\":\"uia_exception\",\"stage\":\"focused_element\",\"exceptionType\":\"Unavailable\"}";
    assertEquals(failure,WindowsFocusProbe.validate(failure));
    assertTrue(WindowsFocusProbe.validate("invalid output").contains("invalid_json"));
    String own=FOCUS.replace("\"status\":\"ok\"","\"status\":\"ok\",\"probeOwnsFocus\":true,\"processName\":\"powershell\"");
    var node=new com.fasterxml.jackson.databind.ObjectMapper().readTree(WindowsFocusProbe.validate(own));
    assertEquals("unknown",node.get("status").asText());
    assertEquals("powershell",node.get("processName").asText());
    assertEquals("probe_owns_focus",node.get("reason").asText());
    assertTrue(node.get("editable").isNull());
  }
  @Test void focusSurvivesShowUiOverviewAndReachesPlannerWithoutGroundingOnTyping() throws Exception {
    var chat=mock(ChatModel.class);
    when(chat.call(any(Prompt.class))).thenReturn(SpringAiComputerVisionModelTest.response(
        ActionValidationTest.json("TYPE_TEXT","text","\"hello\"")));
    var planner=new SpringAiComputerVisionModel(chat,OpenAiChatOptions::builder,()->false,0);
    var grounding=mock(ChatModel.class);
    var vision=new ShowUiComputerVisionModel(planner::decideOverview,grounding,OpenAiChatOptions::builder,()->false);
    var o=SpringAiComputerVisionModelTest.observation();
    assertInstanceOf(ComputerAction.TypeText.class,vision.decide(new ComputerObservation(o.goal(),o.screenshot(),o.recentHistory(),2,20,null,FOCUS)));
    var prompt=org.mockito.ArgumentCaptor.forClass(Prompt.class);
    verify(chat).call(prompt.capture());
    assertTrue(prompt.getValue().getInstructions().getLast().getText().contains(FOCUS));
    verify(grounding,never()).call(any(Prompt.class));
  }
  @Test void eachStepRefreshesFocusAfterDispatchAndSavesDiagnostics(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
    var calls=new java.util.concurrent.atomic.AtomicInteger();
    var dispatched=new java.util.concurrent.atomic.AtomicBoolean();
    var service=new ComputerUseService(ComputerUseServiceTest::screen,o->{
      if(o.step()==1) { assertEquals(WindowsFocusProbe.UNKNOWN,o.focusState()); return new ComputerAction.Click(new ComputerAction.Target(1,1,"Compose"),.9,ComputerAction.Risk.LOW); }
      assertTrue(dispatched.get()); assertEquals(FOCUS,o.focusState());
      assertFalse(o.recentHistory().isEmpty()); return new ComputerAction.Done("verified");
    },(a,s)->dispatched.set(true),a->{},SafetyPolicy.lowRiskOnly(),()->false,e->{},2,2,new ComputerDiagnostics(dir),
        ()->calls.getAndIncrement()==0 ? WindowsFocusProbe.UNKNOWN : FOCUS);
    assertEquals(ComputerUseResult.Status.DONE,service.run("goal").status());
    assertEquals(2,calls.get());
    try(var files=java.nio.file.Files.walk(dir)) { assertEquals(2,files.filter(p->p.getFileName().toString().equals("focus.json")).count()); }
    catch(java.io.IOException e) { throw new AssertionError(e); }
  }
}
