package dev.mikoto2000.rei.computeruse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.awt.Rectangle;
import java.awt.datatransfer.Clipboard;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/** Robot連携の窓口一覧の結果と状態の契約を検証するテスト。 */
class RobotAdaptersTest {
  /** 画面操作の支援で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void convertsResizedPixelsToLogicalCoordinatesIncludingNegativeOrigins() throws Exception {
    var driver = mock(RobotDriver.class);
    var bounds = new Rectangle(-100, 20, 200, 100);
    when(driver.geometry())
        .thenReturn(new ScreenGeometry(bounds, new Rectangle(-100, 0, 1000, 1000), true));
    var screen =
        new CapturedScreen(new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB), bounds);
    var input =
        new RobotComputerInput(
            driver, new ClipboardPaste(() -> new Clipboard("test"), ms -> {}, 10), ms -> {});
    input.execute(
        new ComputerAction.Click(
            new ComputerAction.Target(100, 100, "button"), .9, ComputerAction.Risk.LOW),
        screen);
    var order = inOrder(driver);
    order.verify(driver).geometry();
    order.verify(driver).move(-50, 70);
    order.verify(driver).mousePress(InputEvent.BUTTON1_DOWN_MASK);
    order.verify(driver).mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
  }

  /** 全対象入力操作一覧実行先への引き渡しおよびについて、キー一覧を解放することを確認する。 */
  @Test
  void allInputActionsDispatchAndReleaseKeys() throws Exception {
    var driver = mock(RobotDriver.class);
    when(driver.geometry())
        .thenReturn(
            new ScreenGeometry(new Rectangle(0, 0, 320, 240), new Rectangle(0, 0, 640, 480), true));
    var clipboard = new Clipboard("test");
    var input =
        new RobotComputerInput(driver, new ClipboardPaste(() -> clipboard, ms -> {}, 10), ms -> {});
    var screen = ComputerUseServiceTest.screen();
    input.execute(
        new ComputerAction.DoubleClick(
            new ComputerAction.Target(10, 20, "editor"), .9, ComputerAction.Risk.LOW),
        screen);
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
    for (var action :
        java.util.List.of(
            new ComputerAction.Done("ok"),
            new ComputerAction.Failed("no"),
            new ComputerAction.Wait(1)))
      assertThrows(IllegalArgumentException.class, () -> input.execute(action, screen));
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(driver);
  }

  /** 以外主要なを受け付けないことを確認する。 */
  @Test
  void rejectsNonPrimaryAndChangedBoundsBeforeInput() throws Exception {
    var driver = mock(RobotDriver.class);
    var input =
        new RobotComputerInput(
            driver, new ClipboardPaste(() -> new Clipboard("test"), ms -> {}, 10), ms -> {});
    var click =
        new ComputerAction.Click(
            new ComputerAction.Target(10, 20, "button"), .9, ComputerAction.Risk.LOW);
    for (var geometry :
        java.util.List.of(
            new ScreenGeometry(new Rectangle(0, 0, 320, 240), new Rectangle(0, 0, 640, 480), false),
            new ScreenGeometry(
                new Rectangle(0, 0, 100, 100), new Rectangle(0, 0, 640, 480), true))) {
      when(driver.geometry()).thenReturn(geometry);
      assertThrows(
          IllegalArgumentException.class,
          () -> input.execute(click, ComputerUseServiceTest.screen()));
    }
    verify(driver, never()).move(anyInt(), anyInt());
  }

  /** 画面操作の支援で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void capturesOnlyPrimaryAndUsesAbstraction() throws Exception {
    var driver = mock(RobotDriver.class);
    var bounds = new Rectangle(0, 0, 320, 240);
    when(driver.geometry()).thenReturn(new ScreenGeometry(bounds, bounds, true));
    when(driver.capture(bounds)).thenReturn(ComputerUseServiceTest.screen().image());
    assertEquals(bounds, new RobotScreenCapture(driver).captureScreen().bounds());
    when(driver.geometry()).thenReturn(new ScreenGeometry(bounds, bounds, false));
    assertThrows(
        IllegalArgumentException.class, () -> new RobotScreenCapture(driver).captureScreen());
  }

  /** 追加された待機の実行先を利用することを確認する。 */
  @Test
  void stabilizationUsesInjectedSleeper() throws Exception {
    var waits = new ArrayList<Long>();
    var stabilizer = new FixedUiStabilizer(waits::add, 500);
    stabilizer.awaitAfter(new ComputerAction.Wait(100));
    stabilizer.awaitAfter(new ComputerAction.Uncertain("Loading"));
    assertEquals(java.util.List.of(100L, 500L), waits);
  }
}
