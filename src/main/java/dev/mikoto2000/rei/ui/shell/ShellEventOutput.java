package dev.mikoto2000.rei.ui.shell;

/** Output boundary used by the Shell event renderer. */
public interface ShellEventOutput {
  void print(String text);
  void println(String text);
  void flush();
}
