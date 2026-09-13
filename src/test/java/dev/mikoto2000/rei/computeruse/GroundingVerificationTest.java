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
  @Test void pointEvidenceUsesSelectedDisplayAndIsPassedOnlyToVisualIdentification() throws Exception {
    var chat=mock(ChatModel.class);
    when(chat.call(any(Prompt.class))).thenReturn(
        SpringAiComputerVisionModelTest.response("{\"elementType\":\"input\",\"label\":\"Compose\",\"certain\":true}"),
        SpringAiComputerVisionModelTest.response("{\"approved\":true,\"expectedType\":\"input\",\"reason\":\"matches\"}"));
    var queried=new java.util.ArrayList<java.awt.Point>();
    var verifier=new PlannerGroundingVerifier(chat,OpenAiChatOptions::builder,()->false,p->{
      queried.add(p); return "{\"status\":\"ok\",\"controlType\":\"ControlType.Edit\",\"name\":\"Compose\"}";
    });
    var bounds=new java.awt.Rectangle(-3840,0,3840,2160);
    var display=new DisplayCapture(new ScreenGeometry("left",bounds,new java.awt.Rectangle(-3840,0,7680,2160),false,1,1),
        new java.awt.image.BufferedImage(1920,1080,java.awt.image.BufferedImage.TYPE_INT_RGB));
    var o=new ComputerObservation("SECRET_GOAL",new CapturedScreen(List.of(display)),List.of("SECRET_HISTORY"),1,20,diagnostics);
    verifier.verifyPoint(o,display,"SECRET_TARGET",new double[]{.3,.13});
    assertEquals(List.of(new java.awt.Point(-2688,280)),queried);
    var prompts=org.mockito.ArgumentCaptor.forClass(Prompt.class);
    verify(chat,times(2)).call(prompts.capture());
    String blind=prompts.getAllValues().getFirst().getInstructions().getFirst().getText();
    assertTrue(blind.contains("ControlType.Edit"));
    assertTrue(blind.contains("Compose"));
    assertFalse(blind.contains("SECRET"));
    var saved=new com.fasterxml.jackson.databind.ObjectMapper().readTree(diagnostics.resolve("step-001/point-description-uia.json").toFile());
    assertEquals(-2688,saved.get("desktopX").asInt());
    assertEquals("left",saved.get("displayId").asText());
  }

  @Test void unavailablePointEvidenceCannotBypassVisualRejection() {
    var chat=mock(ChatModel.class);
    when(chat.call(any(Prompt.class))).thenReturn(SpringAiComputerVisionModelTest.response(
        "{\"elementType\":\"unknown\",\"label\":\"uncertain\",\"certain\":false}"));
    var verifier=new PlannerGroundingVerifier(chat,OpenAiChatOptions::builder,()->false,p->{throw new IllegalStateException();});
    var o=SpringAiComputerVisionModelTest.observation();
    assertThrows(InvalidComputerDecision.class,()->verifier.verifyPoint(o,o.screenshot().displays().getFirst(),"input",new double[]{.5,.5}));
    verify(chat).call(any(Prompt.class));
  }
  @ParameterizedTest @EnumSource(GroundingProtocol.class)
  void confirmationRequiredClicksAreGroundedAndVerifiedButStillBlocked(GroundingProtocol protocol) throws Exception {
    for (boolean doubleClick : List.of(false,true)) {
      var grounding=mock(ChatModel.class);
      when(grounding.call(any(Prompt.class))).thenReturn(SpringAiComputerVisionModelTest.response(
          protocol==GroundingProtocol.SHOWUI ? "[0.75,0.25]" : "(750,250)"),
          SpringAiComputerVisionModelTest.response(protocol==GroundingProtocol.SHOWUI ? "[0.5,0.5]" : "(500,500)"));
      var gates=new java.util.ArrayList<String>();
      var target=new ComputerAction.Target(1,1,"Post");
      ComputerAction proposed=doubleClick
          ? new ComputerAction.DoubleClick(target,.9,ComputerAction.Risk.CONFIRM_REQUIRED)
          : new ComputerAction.Click(target,.9,ComputerAction.Risk.CONFIRM_REQUIRED);
      var vision=new ShowUiComputerVisionModel(o->proposed,grounding,OpenAiChatOptions::builder,()->false,protocol,
          (o,i,t,p)->gates.add(p==null ? "crop" : "point"));
      var screen=ComputerUseServiceTest.screen();
      var result=vision.decide(new ComputerObservation("goal",screen,List.of(),1,20));
      assertEquals(proposed.getClass(),result.getClass());
      assertEquals(ComputerAction.Risk.CONFIRM_REQUIRED,result.risk());
      var actual=result instanceof ComputerAction.Click a ? a.target() : ((ComputerAction.DoubleClick)result).target();
      assertNotEquals(1,actual.x());
      assertEquals(.75,actual.normalizedX(),.01);
      assertEquals(.25,actual.normalizedY(),.01);
      assertEquals(List.of("crop","point"),gates);
      verify(grounding,times(2)).call(any(Prompt.class));
      var service=new ComputerUseService(()->screen,o->result,(a,s)->fail("confirmation still required"),a->{},
          SafetyPolicy.lowRiskOnly(),()->false,e->{},1,1);
      assertEquals(ComputerUseResult.Status.SAFETY_BLOCKED,service.run("goal").status());
    }
  }

  @ParameterizedTest @EnumSource(GroundingProtocol.class)
  void confirmationRequiredClickCannotBypassEitherVerificationGate(GroundingProtocol protocol) {
    for (int gate=0;gate<2;gate++) {
      final int rejectedGate=gate;
      var grounding=mock(ChatModel.class);
      when(grounding.call(any(Prompt.class))).thenReturn(SpringAiComputerVisionModelTest.response(
          protocol==GroundingProtocol.SHOWUI ? "[0.5,0.5]" : "(500,500)"));
      var calls=new java.util.concurrent.atomic.AtomicInteger();
      var vision=new ShowUiComputerVisionModel(o->new ComputerAction.Click(new ComputerAction.Target(1,1,"Post"),
          .9,ComputerAction.Risk.CONFIRM_REQUIRED),grounding,OpenAiChatOptions::builder,()->false,protocol,
          (o,i,t,p)->{if(calls.getAndIncrement()==rejectedGate)throw new InvalidComputerDecision("rejected");});
      assertThrows(InvalidComputerDecision.class,()->vision.decide(SpringAiComputerVisionModelTest.observation()));
      verify(grounding,times(gate+1)).call(any(Prompt.class));
    }
  }
  @Test void comparisonOutputLimitReportsStageAndDoesNotRetry() {
    var chat=mock(ChatModel.class);
    when(chat.call(any(Prompt.class))).thenReturn(
        SpringAiComputerVisionModelTest.response("{\"elementType\":\"input\",\"label\":\"Compose\",\"certain\":true}"),
        new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(
            new org.springframework.ai.chat.messages.AssistantMessage(""),
            org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason("length").build()))));
    var verifier=new PlannerGroundingVerifier(chat,OpenAiChatOptions::builder,()->false);
    var o=SpringAiComputerVisionModelTest.observation();
    var error=assertThrows(InvalidComputerDecision.class,()->verifier.verify(o,o.screenshot().displays().getFirst().image(),"Compose",new double[]{.5,.5}));
    assertTrue(error.getMessage().contains("point-verification reached output token limit"));
    verify(chat,times(2)).call(any(Prompt.class));
  }
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
  @Test void verifierSendsOverviewAndDetailAndRecordsVerdicts() throws Exception {
    var chat=mock(ChatModel.class);
    when(chat.call(any(Prompt.class))).thenReturn(SpringAiComputerVisionModelTest.response("{\"approved\":true,\"reason\":\"visible\"}"),
        SpringAiComputerVisionModelTest.response("{\"elementType\":\"button\",\"label\":\"Post\",\"certain\":true}"),
        SpringAiComputerVisionModelTest.response("{\"approved\":true,\"expectedType\":\"button\",\"reason\":\"matches\"}"));
    var verifier=new PlannerGroundingVerifier(chat,OpenAiChatOptions::builder,()->false);
    var screen=ComputerUseServiceTest.screen();
    var image=screen.displays().getFirst().image();
    var o=new ComputerObservation("goal",screen,List.of(),1,20,diagnostics);
    verifier.verify(o,image,"button",null);
    verifier.verify(o,image,"button",new double[]{.5,.5});
    assertTrue(java.nio.file.Files.exists(diagnostics.resolve("step-001/crop-verification-input.png")));
    var marked=javax.imageio.ImageIO.read(diagnostics.resolve("step-001/point-description-input.png").toFile());
    boolean hasRed=false;
    for(int y=0;y<marked.getHeight();y++)for(int x=0;x<marked.getWidth();x++)if(marked.getRGB(x,y)==java.awt.Color.RED.getRGB())hasRed=true;
    assertTrue(hasRed);
    var detail=javax.imageio.ImageIO.read(diagnostics.resolve("step-001/point-description-detail.png").toFile());
    assertNotNull(detail);
    assertTrue(java.nio.file.Files.exists(diagnostics.resolve("step-001/point-description-geometry.json")));
    assertTrue(java.nio.file.Files.readString(diagnostics.resolve("step-001/point-verification-response.txt")).contains("approved"));
    verify(chat,times(3)).call(any(Prompt.class));
  }
  @Test void blindDescriptionDoesNotReceiveTargetAndTypeMismatchRejectsEvenApproval() throws Exception {
    var chat=mock(ChatModel.class);
    when(chat.call(any(Prompt.class))).thenReturn(
        SpringAiComputerVisionModelTest.response("{\"elementType\":\"button\",\"label\":\"Post\",\"certain\":true}"),
        SpringAiComputerVisionModelTest.response("{\"approved\":true,\"expectedType\":\"input\",\"reason\":\"matches\"}"));
    var verifier=new PlannerGroundingVerifier(chat,OpenAiChatOptions::builder,()->false);
    var screen=ComputerUseServiceTest.screen();
    var observation=new ComputerObservation("SECRET_GOAL",screen,List.of("SECRET_HISTORY"),1,20);
    assertThrows(InvalidComputerDecision.class,()->verifier.verify(observation,screen.displays().getFirst().image(),"SECRET_TARGET input",new double[]{.5,.5}));
    var prompts=org.mockito.ArgumentCaptor.forClass(Prompt.class);
    verify(chat,times(2)).call(prompts.capture());
    var blind=(org.springframework.ai.chat.messages.UserMessage)prompts.getAllValues().get(0).getInstructions().getFirst();
    assertFalse(blind.getText().contains("SECRET"));
    assertEquals(2,blind.getMedia().size());
    var comparison=(org.springframework.ai.chat.messages.UserMessage)prompts.getAllValues().get(1).getInstructions().getFirst();
    assertTrue(comparison.getText().contains("SECRET_TARGET"));
    assertTrue(comparison.getMedia().isEmpty());
  }
  @Test void detailUsesNativePixelsAndKeepsPointCorrectAtScreenEdges() {
    var image = new java.awt.image.BufferedImage(3840,2160,java.awt.image.BufferedImage.TYPE_INT_RGB);
    var g = image.createGraphics();
    g.setColor(java.awt.Color.WHITE); g.fillRect(0,0,3840,2160); g.dispose();
    for (double[] point : List.of(new double[]{.27,.13},new double[]{0,0},new double[]{1,1},new double[]{1,0},new double[]{0,1})) {
      int x=Math.min(3839,(int)(point[0]*3840)), y=Math.min(2159,(int)(point[1]*2160));
      image.setRGB(x,y,java.awt.Color.BLUE.getRGB());
      var detail=PlannerGroundingVerifier.detail(image,point);
      assertEquals(960,detail.width()); assertEquals(540,detail.height());
      assertEquals(1200,detail.image().getWidth()); assertEquals(675,detail.image().getHeight());
      assertTrue(detail.left()>=0 && detail.left()+detail.width()<=3840);
      assertTrue(detail.top()>=0 && detail.top()+detail.height()<=2160);
      int localX=(int)((double)(x-detail.left())/detail.width()*detail.image().getWidth());
      int localY=(int)((double)(y-detail.top())/detail.height()*detail.image().getHeight());
      var center=new java.awt.Color(detail.image().getRGB(localX,localY));
      assertTrue(center.getBlue()>center.getRed(),"Native pixel at target survives resizing and ring leaves center visible");
      int ringX=localX+10<1200 ? localX+10 : localX-10;
      assertEquals(java.awt.Color.RED.getRGB(),detail.image().getRGB(ringX,localY));
      assertEquals(java.awt.Color.BLUE.getRGB(),image.getRGB(x,y),"Source is never marked");
    }
    var tiny=PlannerGroundingVerifier.detail(new java.awt.image.BufferedImage(1,1,1),new double[]{1,1});
    assertEquals(2,tiny.image().getWidth());
    assertThrows(InvalidComputerDecision.class,()->PlannerGroundingVerifier.detail(image,new double[]{Double.NaN,.5}));
  }
  @Test void uncertainObservationStopsBeforeComparison() {
    var chat=mock(ChatModel.class);
    when(chat.call(any(Prompt.class))).thenReturn(SpringAiComputerVisionModelTest.response(
        "{\"elementType\":\"unknown\",\"label\":\"unclear\",\"certain\":false}"));
    var screen=ComputerUseServiceTest.screen();
    var verifier=new PlannerGroundingVerifier(chat,OpenAiChatOptions::builder,()->false);
    assertThrows(InvalidComputerDecision.class,()->verifier.verify(new ComputerObservation("goal",screen,List.of(),1,20),
        screen.displays().getFirst().image(),"input",new double[]{.5,.5}));
    verify(chat).call(any(Prompt.class));
  }
  @Test void descriptionsAndComparisonsRejectMalformedEvidence() {
    for(String bad:List.of("{}","{\"elementType\":\"button\",\"label\":\"Post\",\"certain\":\"true\"}",
        "{\"elementType\":\"button\",\"label\":\"Post\",\"certain\":true} trailing"))
      assertThrows(InvalidComputerDecision.class,()->PlannerGroundingVerifier.parseDescription(bad));
    assertThrows(InvalidComputerDecision.class,()->PlannerGroundingVerifier.requireMatch(
        "{\"approved\":true,\"expectedType\":\"input\",\"reason\":\"ok\"}","button"));
    assertThrows(InvalidComputerDecision.class,()->PlannerGroundingVerifier.requireMatch(
        "{\"approved\":false,\"expectedType\":\"button\",\"reason\":\"wrong label\"}","button"));
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
