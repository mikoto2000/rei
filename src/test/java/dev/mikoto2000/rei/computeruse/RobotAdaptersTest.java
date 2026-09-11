package dev.mikoto2000.rei.computeruse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class RobotAdaptersTest {
  @Test void convertsResizedPixelsToLogicalCoordinatesIncludingNegativeOrigins() throws Exception {
    var driver = mock(RobotDriver.class);
    var bounds = new Rectangle(-100, 20, 200, 100);
    when(driver.geometry()).thenReturn(new ScreenGeometry(bounds, new Rectangle(-100,0,1000,1000), true));
    var screen = new CapturedScreen(new BufferedImage(400,200,BufferedImage.TYPE_INT_RGB), bounds);
    var input = new RobotComputerInput(driver, new ClipboardPaste(() -> new Clipboard("test"), ms -> {}, 10), ms -> {});
    input.execute(new ComputerAction.Click(new ComputerAction.Target(100,100,"button"), .9, ComputerAction.Risk.LOW), screen);
    var order = inOrder(driver);
    order.verify(driver).geometry();
    order.verify(driver).move(-50,70);
    order.verify(driver).mousePress(InputEvent.BUTTON1_DOWN_MASK);
    order.verify(driver).mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
  }

  @Test void allInputActionsDispatchAndReleaseKeys() throws Exception {
    var driver = mock(RobotDriver.class);
    when(driver.geometry()).thenReturn(new ScreenGeometry(new Rectangle(0,0,320,240), new Rectangle(0,0,640,480), true));
    var clipboard = new Clipboard("test");
    var input = new RobotComputerInput(driver, new ClipboardPaste(() -> clipboard, ms -> {}, 10), ms -> {});
    var screen = ComputerUseServiceTest.screen();
    input.execute(new ComputerAction.DoubleClick(new ComputerAction.Target(10,20,"editor"), .9, ComputerAction.Risk.LOW), screen);
    verify(driver, times(2)).mousePress(InputEvent.BUTTON1_DOWN_MASK);
    verify(driver, times(2)).mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    input.execute(new ComputerAction.PressKey("ENTER", ComputerAction.Risk.LOW), screen);
    verify(driver).keyPress(KeyEvent.VK_ENTER);
    verify(driver).keyRelease(KeyEvent.VK_ENTER);
    input.execute(new ComputerAction.Scroll(-2, ComputerAction.Risk.LOW), screen);
    verify(driver).wheel(-2);
    input.execute(new ComputerAction.TypeText("日本語😀", ComputerAction.Risk.LOW), screen);
    verify(driver).keyPress(KeyEvent.VK_CONTROL);
    verify(driver).keyPress(KeyEvent.VK_V);
    verify(driver).keyRelease(KeyEvent.VK_V);
    verify(driver).keyRelease(KeyEvent.VK_CONTROL);
    clearInvocations(driver);
    for (var action : java.util.List.of(new ComputerAction.Done("ok"), new ComputerAction.Failed("no"), new ComputerAction.Wait(1)))
      assertThrows(IllegalArgumentException.class, () -> input.execute(action, screen));
    verifyNoInteractions(driver);
  }

  @Test void rejectsNonPrimaryAndChangedBoundsBeforeInput() throws Exception {
    var driver = mock(RobotDriver.class);
    var input = new RobotComputerInput(driver, new ClipboardPaste(() -> new Clipboard("test"), ms -> {}, 10), ms -> {});
    var click = new ComputerAction.Click(new ComputerAction.Target(10,20,"button"), .9, ComputerAction.Risk.LOW);
    for (var geometry : java.util.List.of(
        new ScreenGeometry(new Rectangle(0,0,320,240),new Rectangle(0,0,640,480),false),
        new ScreenGeometry(new Rectangle(0,0,100,100),new Rectangle(0,0,640,480),true))) {
      when(driver.geometry()).thenReturn(geometry);
      assertThrows(IllegalArgumentException.class, () -> input.execute(click, ComputerUseServiceTest.screen()));
    }
    verify(driver, never()).move(anyInt(),anyInt());
  }

  @Test void capturesOnlyPrimaryAndUsesAbstraction() throws Exception {
    var driver = mock(RobotDriver.class);
    var bounds = new Rectangle(0,0,320,240);
    when(driver.geometry()).thenReturn(new ScreenGeometry(bounds,bounds,true));
    when(driver.capture(bounds)).thenReturn(ComputerUseServiceTest.screen().image());
    assertEquals(bounds, new RobotScreenCapture(driver).captureScreen().bounds());
    when(driver.geometry()).thenReturn(new ScreenGeometry(bounds,bounds,false));
    assertThrows(IllegalArgumentException.class, () -> new RobotScreenCapture(driver).captureScreen());
  }

  @Test void stabilizationUsesInjectedSleeper() throws Exception {
    var waits = new ArrayList<Long>();
    var stabilizer = new FixedUiStabilizer(waits::add, 500);
    stabilizer.awaitAfter(new ComputerAction.Wait(100));
    stabilizer.awaitAfter(new ComputerAction.Uncertain("Loading"));
    assertEquals(java.util.List.of(100L,500L), waits);
  }
}
