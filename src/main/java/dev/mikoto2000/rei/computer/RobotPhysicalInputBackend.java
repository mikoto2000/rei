package dev.mikoto2000.rei.computer;

import java.awt.AWTException;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Locale;
import java.util.function.Supplier;

public class RobotPhysicalInputBackend implements PhysicalInputBackend {
  private final Supplier<Robot> robotSupplier;
  private volatile Robot robot;

  public RobotPhysicalInputBackend() {
    this(RobotPhysicalInputBackend::createRobot);
  }

  RobotPhysicalInputBackend(Robot robot) {
    this(() -> robot);
  }

  RobotPhysicalInputBackend(Supplier<Robot> robotSupplier) {
    this.robotSupplier = robotSupplier;
  }

  @Override
  public ComputerActionResult click(ComputerBounds bounds) {
    Robot current = availableRobot();
    if (current == null) {
      return unavailable();
    }
    moveToCenter(current, bounds);
    current.mousePress(InputEvent.BUTTON1_DOWN_MASK);
    current.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    return ComputerActionResult.success(ComputerActionBackend.ROBOT, true);
  }

  @Override
  public ComputerActionResult doubleClick(ComputerBounds bounds) {
    ComputerActionResult first = click(bounds);
    if (!first.success()) {
      return first;
    }
    ComputerActionResult second = click(bounds);
    if (!second.success()) {
      return second;
    }
    return ComputerActionResult.success(ComputerActionBackend.ROBOT, false);
  }

  @Override
  public ComputerActionResult moveMouse(int x, int y) {
    Robot current = availableRobot();
    if (current == null) {
      return unavailable();
    }
    current.mouseMove(x, y);
    return ComputerActionResult.success(ComputerActionBackend.ROBOT, false);
  }

  @Override
  public ComputerActionResult typeText(String text) {
    Robot current = availableRobot();
    if (current == null) {
      return unavailable();
    }
    if (text == null || text.isEmpty()) {
      return ComputerActionResult.success(ComputerActionBackend.ROBOT, false);
    }
    for (char c : text.toCharArray()) {
      typeChar(current, c);
    }
    return ComputerActionResult.success(ComputerActionBackend.ROBOT, false);
  }

  @Override
  public ComputerActionResult keyPress(String keyStroke) {
    Integer code = keyCode(keyStroke);
    if (code == null) {
      return ComputerActionResult.failure("unsupported key: " + keyStroke, ComputerActionBackend.ROBOT, false);
    }
    Robot current = availableRobot();
    if (current == null) {
      return unavailable();
    }
    current.keyPress(code);
    current.keyRelease(code);
    return ComputerActionResult.success(ComputerActionBackend.ROBOT, false);
  }

  @Override
  public ComputerActionResult scroll(int wheelAmount) {
    Robot current = availableRobot();
    if (current == null) {
      return unavailable();
    }
    current.mouseWheel(wheelAmount);
    return ComputerActionResult.success(ComputerActionBackend.ROBOT, false);
  }

  @Override
  public ComputerActionResult drag(ComputerBounds from, ComputerBounds to) {
    Robot current = availableRobot();
    if (current == null) {
      return unavailable();
    }
    moveToCenter(current, from);
    current.mousePress(InputEvent.BUTTON1_DOWN_MASK);
    moveToCenter(current, to);
    current.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    return ComputerActionResult.success(ComputerActionBackend.ROBOT, false);
  }

  private void moveToCenter(Robot robot, ComputerBounds bounds) {
    java.awt.Point center = bounds.center();
    robot.mouseMove(center.x, center.y);
  }

  private void typeChar(Robot robot, char c) {
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
      Robot robot = new Robot();
      robot.setAutoDelay(30);
      return robot;
    } catch (AWTException e) {
      throw new IllegalStateException("Robot backend is not available", e);
    }
  }

  private Robot availableRobot() {
    Robot current = robot;
    if (current != null) {
      return current;
    }
    try {
      current = robotSupplier.get();
      robot = current;
      return current;
    } catch (IllegalStateException e) {
      return null;
    }
  }

  private ComputerActionResult unavailable() {
    return ComputerActionResult.failure("Robot backend is not available", ComputerActionBackend.ROBOT, false);
  }
}
