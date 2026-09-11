package dev.mikoto2000.rei.computeruse;

import java.awt.Point;
import java.awt.event.*;

public final class RobotComputerInput implements ComputerInput {
  private final RobotDriver driver;
  private final ClipboardPaste clipboard;
  private final Sleeper sleeper;
  public RobotComputerInput(RobotDriver driver, ClipboardPaste clipboard, Sleeper sleeper) {
    this.driver = driver; this.clipboard = clipboard; this.sleeper = sleeper;
  }
  public void execute(ComputerAction action, CapturedScreen screen) throws Exception {
    ActionValidator.validate(action, screen);
    if (action instanceof ComputerAction.Done || action instanceof ComputerAction.Failed
        || action instanceof ComputerAction.Wait || action instanceof ComputerAction.Uncertain)
      throw new IllegalArgumentException("Not an input action");
    var current = RobotScreenCapture.currentDisplays(driver);
    for (var captured : screen.displays()) {
      var observed = captured.geometry();
      if (current.stream().noneMatch(g -> g.id().equals(observed.id()) && g.bounds().equals(observed.bounds())
          && g.scaleX() == observed.scaleX() && g.scaleY() == observed.scaleY() && g.primary() == observed.primary()))
        throw new IllegalArgumentException("Display changed since observation");
    }
    if (current.size() != screen.displays().size()) throw new IllegalArgumentException("Display topology changed since observation");
    var selected = action instanceof ComputerAction.Click a ? screen.display(a.target().displayId())
        : action instanceof ComputerAction.DoubleClick a ? screen.display(a.target().displayId()) : screen.displays().getFirst();
    driver.selectDisplay(selected.geometry());
    switch (action) {
      case ComputerAction.Click a -> click(selected.desktopPoint(a.target()));
      case ComputerAction.DoubleClick a -> {
        Point point = selected.desktopPoint(a.target());
        click(point); sleeper.sleep(100); click(point);
      }
      case ComputerAction.TypeText a -> clipboard.paste(a.text(), () -> {
        try { driver.keyPress(KeyEvent.VK_CONTROL); key(KeyEvent.VK_V); }
        finally { driver.keyRelease(KeyEvent.VK_CONTROL); }
      });
      case ComputerAction.PressKey a -> key(keyCode(a.key()));
      case ComputerAction.Scroll a -> driver.wheel(a.amount());
      default -> throw new IllegalArgumentException("Not an input action");
    }
  }
  private void click(Point point) {
    driver.move(point.x, point.y);
    try { driver.mousePress(InputEvent.BUTTON1_DOWN_MASK); }
    finally { driver.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); }
  }
  private void key(int code) {
    try { driver.keyPress(code); } finally { driver.keyRelease(code); }
  }
  private static int keyCode(String key) {
    return switch (key) {
      case "ENTER" -> KeyEvent.VK_ENTER; case "TAB" -> KeyEvent.VK_TAB; case "ESCAPE" -> KeyEvent.VK_ESCAPE;
      case "BACKSPACE" -> KeyEvent.VK_BACK_SPACE; case "DELETE" -> KeyEvent.VK_DELETE;
      case "UP" -> KeyEvent.VK_UP; case "DOWN" -> KeyEvent.VK_DOWN; case "LEFT" -> KeyEvent.VK_LEFT;
      case "RIGHT" -> KeyEvent.VK_RIGHT; case "HOME" -> KeyEvent.VK_HOME; case "END" -> KeyEvent.VK_END;
      case "PAGE_UP" -> KeyEvent.VK_PAGE_UP; case "PAGE_DOWN" -> KeyEvent.VK_PAGE_DOWN; case "SPACE" -> KeyEvent.VK_SPACE;
      default -> throw new IllegalArgumentException("Unknown key");
    };
  }
}
