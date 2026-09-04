package dev.mikoto2000.rei.computer;

public interface PhysicalInputBackend {
  ComputerActionResult click(ComputerBounds bounds);

  ComputerActionResult doubleClick(ComputerBounds bounds);

  ComputerActionResult moveMouse(int x, int y);

  ComputerActionResult typeText(String text);

  ComputerActionResult keyPress(String keyStroke);

  ComputerActionResult scroll(int wheelAmount);

  ComputerActionResult drag(ComputerBounds from, ComputerBounds to);
}
