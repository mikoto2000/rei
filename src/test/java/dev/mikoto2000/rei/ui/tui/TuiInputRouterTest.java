package dev.mikoto2000.rei.ui.tui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import dev.mikoto2000.rei.core.command.UserInputParser;
import dev.mikoto2000.rei.core.command.UserInputService;

class TuiInputRouterTest {

  private final TuiInputRouter router = new TuiInputRouter(
      new UserInputService(new UserInputParser()));

  @Test
  void routesChatAndSlashWithoutSendingSlashToChat() {
    assertEquals(TuiInputRouter.Kind.CHAT, router.route("hello", false).kind());
    TuiInputRouter.Route slash = router.route("/model foo", false);
    assertEquals(TuiInputRouter.Kind.COMMAND, slash.kind());
    assertArrayEquals(new String[] {"model", "foo"}, slash.arguments());
  }

  @Test
  void handlesExitNestedTuiAndShellOnlyCommands() {
    assertEquals(TuiInputRouter.Kind.EXIT, router.route("/exit", false).kind());
    assertEquals(TuiInputRouter.Kind.EXIT, router.route("/quit", false).kind());
    assertEquals("Already in TUI mode.", router.route("/tui", false).message());
    assertEquals("This command is not available in TUI mode.", router.route("/sh", false).message());
    assertEquals("This command is not available in TUI mode.", router.route("/paste", false).message());
  }

  @Test
  void allowsHelpWhileRunningButRejectsMutatingAndChatInput() {
    assertEquals(TuiInputRouter.Kind.COMMAND, router.route("/help", true).kind());
    assertEquals(TuiInputRouter.Kind.MESSAGE, router.route("/model foo", true).kind());
    assertEquals(TuiInputRouter.Kind.MESSAGE, router.route("hello", true).kind());
  }
}
