package dev.mikoto2000.rei.ui.shell;

/** Output boundary used by the Shell event renderer. */
public interface ShellEventOutput {
  void print(String text);

  void println(String text);

  void flush();

  default void approvalActions(String project, String session, String id) {}

  default void approvalRequired(String message) {
    println(dev.mikoto2000.rei.core.completion.ApprovalNotice.text(message));
  }
}
