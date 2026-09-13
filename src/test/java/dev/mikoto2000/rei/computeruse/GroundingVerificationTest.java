package dev.mikoto2000.rei.computeruse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

class GroundingVerificationTest {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path diagnostics;
  @Test void verdictMustBeExplicitStrictApproval() {
    PlannerGroundingVerifier.requireApproval("{\"approved\":true,\"reason\":\"visible\"}","test");
    for(var text:List.of("{}","{\"approved\":\"true\",\"reason\":\"ok\"}","{\"approved\":false,\"reason\":\"missing\"}",
        "{\"approved\":true,\"reason\":\"ok\"} trailing", "{\"approved\":false,\"approved\":true,\"reason\":\"ok\"}"))
      assertThrows(InvalidComputerDecision.class,()->PlannerGroundingVerifier.requireApproval(text,"test"));
  }
  @ParameterizedTest @EnumSource(GroundingProtocol.class)
  void rejectionAtEitherGatePreventsDispatch(GroundingProtocol protocol) {
    for(int rejectedGate=0;rejectedGate<2;rejectedGate++) {
      int gate=rejectedGate;
      var grounding=mock(ChatModel.class);
      when(grounding.call(any(Prompt.class))).thenReturn(SpringAiComputerVisionModelTest.response(
          protocol==GroundingProtocol.SHOWUI ? "[0.5,0.5]" : "(500,500)"));
      var calls=new java.util.concurrent.atomic.AtomicInteger();
      GroundingVerifier verifier=(o,i,t,p)->{if(calls.getAndIncrement()==gate)throw new InvalidComputerDecision("rejected");};
      var vision=new ShowUiComputerVisionModel(o->new ComputerAction.Click(new ComputerAction.Target(1,1,"button"),.9,ComputerAction.Risk.LOW),
          grounding,OpenAiChatOptions::builder,()->false,protocol,verifier);
      var service=new ComputerUseService(ComputerUseServiceTest::screen,vision,(a,s)->fail("must not click"),a->{},
          SafetyPolicy.lowRiskOnly(),()->false,e->{},2,2);
      assertEquals(ComputerUseResult.Status.MODEL_ERROR,service.run("goal").status());
      verify(grounding,times(gate+1)).call(any(Prompt.class));
    }
  }
  @Test void verifierSendsCropAndMarkedFullImageAndRecordsVerdicts() throws Exception {
    var chat=mock(ChatModel.class);
    when(chat.call(any(Prompt.class))).thenReturn(SpringAiComputerVisionModelTest.response("{\"approved\":true,\"reason\":\"visible\"}"));
    var verifier=new PlannerGroundingVerifier(chat,OpenAiChatOptions::builder,()->false);
    var screen=ComputerUseServiceTest.screen();
    var image=screen.displays().getFirst().image();
    var o=new ComputerObservation("goal",screen,List.of(),1,20,diagnostics);
    verifier.verify(o,image,"button",null);
    verifier.verify(o,image,"button",new double[]{.5,.5});
    assertTrue(java.nio.file.Files.exists(diagnostics.resolve("step-001/crop-verification-input.png")));
    var marked=javax.imageio.ImageIO.read(diagnostics.resolve("step-001/point-verification-input.png").toFile());
    boolean hasRed=false;
    for(int y=0;y<marked.getHeight();y++)for(int x=0;x<marked.getWidth();x++)if(marked.getRGB(x,y)==java.awt.Color.RED.getRGB())hasRed=true;
    assertTrue(hasRed);
    assertTrue(java.nio.file.Files.readString(diagnostics.resolve("step-001/point-verification-response.txt")).contains("approved"));
    verify(chat,times(2)).call(any(Prompt.class));
  }
  @ParameterizedTest @EnumSource(GroundingProtocol.class)
  void bothApprovalsPrecedeReturnedClick(GroundingProtocol protocol) throws Exception {
    var grounding=mock(ChatModel.class);
    when(grounding.call(any(Prompt.class))).thenReturn(SpringAiComputerVisionModelTest.response(
        protocol==GroundingProtocol.SHOWUI ? "[0.5,0.5]" : "(500,500)"));
    var screen=ComputerUseServiceTest.screen();
    var source=screen.displays().getFirst().image();
    var gates=new java.util.ArrayList<String>();
    var vision=new ShowUiComputerVisionModel(o->new ComputerAction.Click(new ComputerAction.Target(1,1,"button"),.9,ComputerAction.Risk.LOW),
        grounding,OpenAiChatOptions::builder,()->false,protocol,(o,i,t,p)->{
          assertEquals("button",t);
          if(p==null){assertEquals(source.getWidth()/2,i.getWidth());gates.add("crop");}
          else {assertEquals(List.of("crop"),gates);assertEquals(source.getWidth(),i.getWidth());assertArrayEquals(new double[]{.5,.5},p);gates.add("point");}
        });
    assertInstanceOf(ComputerAction.Click.class,vision.decide(new ComputerObservation("goal",screen,List.of(),1,20)));
    assertEquals(List.of("crop","point"),gates);
  }
}
