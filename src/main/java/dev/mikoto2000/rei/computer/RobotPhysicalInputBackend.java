package dev.mikoto2000.rei.computer;

import java.awt.AWTException;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Locale;

public class RobotPhysicalInputBackend implements PhysicalInputBackend {
  private final Robot robot;

  public RobotPhysicalInputBackend() {
    this(createRobot());
  }

  RobotPhysicalInputBackend(Robot robot) {
    this.robot = robot;
    this.robot.setAutoDelay(30);
  }

  @Override
  public ComputerActionResult click(ComputerBounds bounds) {
    moveToCenter(bounds);
    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    return ComputerActionResult.success(ComputerActionBackend.ROBOT, true);
  }

  @Override
  public ComputerActionResult doubleClick(ComputerBounds bounds) {
    click(bounds);
    click(bounds);
    return ComputerActionResult.success(ComputerActionBackend.ROBOT, false);
  }

  @Override
  public ComputerActionResult moveMouse(int x, int y) {
    robot.mouseMove(x, y);
    return ComputerActionResult.success(ComputerActionBackend.ROBOT, false);
  }

  @Override
  public ComputerActionResult typeText(String text) {
    if (text == null || text.isEmpty()) {
      return ComputerActionResult.success(ComputerActionBackend.ROBOT, false);
    }
    for (char c : text.toCharArray()) {
      typeChar(c);
    }
    return ComputerActionResult.success(ComputerActionBackend.ROBOT, false);
  }

  @Override
  public ComputerActionResult keyPress(String keyStroke) {
    Integer code = keyCode(keyStroke);
    if (code == null) {
      return ComputerActionResult.failure("unsupported key: " + keyStroke, ComputerActionBackend.ROBOT, false);
    }
    robot.keyPress(code);
    robot.keyRelease(code);
    return ComputerActionResult.success(ComputerActionBackend.ROBOT, false);
  }

  @Override
  public ComputerActionResult scroll(int wheelAmount) {
    robot.mouseWheel(wheelAmount);
    return ComputerActionResult.success(ComputerActionBackend.ROBOT, false);
  }

  @Override
  public ComputerActionResult drag(ComputerBounds from, ComputerBounds to) {
    moveToCenter(from);
    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
    moveToCenter(to);
    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    return ComputerActionResult.success(ComputerActionBackend.ROBOT, false);
  }

  private void moveToCenter(ComputerBounds bounds) {
    java.awt.Point center = bounds.center();
    robot.mouseMove(center.x, center.y);
  }

  private void typeChar(char c) {
    int code = KeyEvent.getExtendedKeyCodeForChar(c);
    if (code == KeyEvent.VK_UNDEFINED) {
      throw new IllegalArgumentException("unsupported character: " + c);
    }
    boolean upper = Character.isUpperCase(c);
    if (upper) {
      robot.keyPress(KeyEvent.VK_SHIFT);
    }
    robot.keyPress(code);
    robot.keyRelease(code);
    if (upper) {
      robot.keyRelease(KeyEvent.VK_SHIFT);
    }
  }

  private Integer keyCode(String keyStroke) {
    if (keyStroke == null || keyStroke.isBlank()) {
      return null;
    }
    try {
      return KeyEvent.class.getField("VK_" + keyStroke.trim().toUpperCase(Locale.ROOT)).getInt(null);
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }

  private static Robot createRobot() {
    try {
      return new Robot();
    } catch (AWTException e) {
      throw new IllegalStateException("Robot backend is not available", e);
    }
  }
}
