package dev.mikoto2000.rei.computeruse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.datatransfer.Clipboard;
import java.util.List;
import org.junit.jupiter.api.Test;

class MultiDisplayTest {
  @Test void mapsFractionalDpiAndNegativeVerticalOrigins() {
    var frame = new DisplayCapture(new ScreenGeometry("above",new Rectangle(0,-600,800,600),
        new Rectangle(0,-600,800,1200),false,1.5,1.5),new BufferedImage(1200,900,BufferedImage.TYPE_INT_RGB));
    assertEquals(new Point(200,-500),frame.desktopPoint(new ComputerAction.Target("above",300,150,"target")));
  }
  static ScreenGeometry left() { return new ScreenGeometry("left", new Rectangle(-800,0,800,600),
      new Rectangle(-800,0,1600,600), false, 2, 2); }
  static ScreenGeometry main() { return new ScreenGeometry("main", new Rectangle(0,0,800,600),
      new Rectangle(-800,0,1600,600), true, 1, 1); }
  static CapturedScreen desktop() { return new CapturedScreen(List.of(
      new DisplayCapture(main(), new BufferedImage(800,600,BufferedImage.TYPE_INT_RGB)),
      new DisplayCapture(left(), new BufferedImage(1600,1200,BufferedImage.TYPE_INT_RGB)))); }
  @Test void routesImageCoordinatesToSelectedMonitorWithMixedScaling() throws Exception {
    var driver = mock(RobotDriver.class);
    when(driver.displays()).thenReturn(List.of(main(), left()));
    var input = new RobotComputerInput(driver, new ClipboardPaste(() -> new Clipboard("test"), ms -> {}, 1), ms -> {});
    input.execute(new ComputerAction.Click(new ComputerAction.Target("left",400,200,"editor"), .9, ComputerAction.Risk.LOW), desktop());
    var order = inOrder(driver);
    order.verify(driver).selectDisplay(left());
    order.verify(driver).move(-600,100);
  }
  @Test void rejectsAmbiguousTargetsAndChangedDpiBeforeInput() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> ActionValidator.validate(
        new ComputerAction.Click(new ComputerAction.Target(10,10,"editor"), .9, ComputerAction.Risk.LOW), desktop()));
    var driver = mock(RobotDriver.class);
    when(driver.displays()).thenReturn(List.of(main(), new ScreenGeometry("left",left().bounds(),left().virtualBounds(),false,1.5,1.5)));
    var input = new RobotComputerInput(driver, new ClipboardPaste(() -> new Clipboard("test"), ms -> {}, 1), ms -> {});
    assertThrows(IllegalArgumentException.class, () -> input.execute(
        new ComputerAction.Click(new ComputerAction.Target("left",10,10,"editor"), .9, ComputerAction.Risk.LOW), desktop()));
    verify(driver,never()).move(anyInt(),anyInt());
  }
  @Test void capturesEachDisplayAndRejectsTopologyChangesDuringCapture() throws Exception {
    var driver = mock(RobotDriver.class);
    when(driver.displays()).thenReturn(List.of(main(),left()));
    when(driver.capture(main().bounds())).thenReturn(desktop().displays().get(0).image());
    when(driver.capture(left().bounds())).thenReturn(desktop().displays().get(1).image());
    assertEquals(2,new RobotScreenCapture(driver).captureScreen().displays().size());
    when(driver.displays()).thenReturn(List.of(main(),left()),List.of(main()));
    assertThrows(IllegalStateException.class, () -> new RobotScreenCapture(driver).captureScreen());
  }
  @Test void parserUsesSelectedImageBoundsAndRejectsUnknownDisplay() {
    String json = ActionValidationTest.json("CLICK", "target", "{\"displayId\":\"left\",\"description\":\"editor\",\"centerX\":0.75,\"centerY\":0.5}")
        .replace("\"confidence\":null", "\"confidence\":0.9");
    var click = (ComputerAction.Click)new ActionParser().parse(json,desktop());
    assertEquals("left",click.target().displayId());
    assertThrows(IllegalArgumentException.class, () -> new ActionParser().parse(json.replace("left","unknown"),desktop()));
  }
  @Test void visionReceivesAllImagesWithUnambiguousLabels() throws Exception {
    var model = mock(org.springframework.ai.chat.model.ChatModel.class);
    when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(
        SpringAiComputerVisionModelTest.response(ActionValidationTest.json("DONE","reason","\"visible\"")));
    new SpringAiComputerVisionModel(model, org.springframework.ai.openai.OpenAiChatOptions::builder, () -> false, 0)
        .decide(new ComputerObservation("goal",desktop(),List.of(),1,20));
    var prompt = org.mockito.ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
    verify(model).call(prompt.capture());
    var user = (org.springframework.ai.chat.messages.UserMessage)prompt.getValue().getInstructions().getLast();
    assertEquals(2,user.getMedia().size());
    assertTrue(user.getText().contains("displayId=left"));
    assertTrue(user.getText().contains("1600x1200"));
  }
}
