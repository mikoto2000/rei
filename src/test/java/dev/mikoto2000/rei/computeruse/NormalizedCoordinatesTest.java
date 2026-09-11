package dev.mikoto2000.rei.computeruse;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class NormalizedCoordinatesTest {
  private String click(String x, String y) {
    return ActionValidationTest.json("CLICK","target","{\"displayId\":\"left\",\"description\":\"button\",\"centerX\":" + x + ",\"centerY\":" + y + "}")
        .replace("\"confidence\":null","\"confidence\":0.9");
  }
  @Test void mapsFractionsToSelectedImageAndDesktop() {
    var screen = MultiDisplayTest.desktop();
    var action = (ComputerAction.Click)new ActionParser().parse(click("0.25","0.5"),screen);
    assertEquals(400,action.target().x());
    assertEquals(600,action.target().y());
    assertEquals(new java.awt.Point(-600,300),screen.display("left").desktopPoint(action.target()));
    var smaller = new CapturedScreen(java.util.List.of(new DisplayCapture(MultiDisplayTest.left(),
        new java.awt.image.BufferedImage(800,600,java.awt.image.BufferedImage.TYPE_INT_RGB))));
    var resizedAction = (ComputerAction.Click)new ActionParser().parse(click("0.25","0.5"),smaller);
    assertEquals(new java.awt.Point(-600,300),smaller.display("left").desktopPoint(resizedAction.target()));
  }
  @Test void endpointsStayInsideImageAndPixelUnitsAreRejected() {
    var screen = MultiDisplayTest.desktop();
    var action = (ComputerAction.Click)new ActionParser().parse(click("1","0"),screen);
    assertEquals(1599,action.target().x()); assertEquals(0,action.target().y());
    for (String x : java.util.List.of("1080","-0.01","1.001","\"0.5\"","null"))
      assertThrows(IllegalArgumentException.class, () -> new ActionParser().parse(click(x,"0.5"),screen));
  }
  @Test void diagnosticsRetainReturnedFractionsAndConvertedPixels(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    var screen = MultiDisplayTest.desktop();
    var action = new ActionParser().parse(click("0.25","0.5"),screen);
    var diagnostics = new ComputerDiagnostics(directory);
    var run = diagnostics.begin();
    diagnostics.observed(run,1,screen);
    diagnostics.action(run,1,screen,action,"dispatched");
    var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(run.resolve("step-001/dispatched.json").toFile());
    assertEquals(0.25,json.get("normalizedX").asDouble());
    assertEquals(0.5,json.get("normalizedY").asDouble());
    assertEquals(400,json.get("imageX").asInt());
    assertEquals(-600,json.get("robotX").asInt());
  }
}
