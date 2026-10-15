package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ApprovalAliasesTest {
  @Test
  void displayedNumbersNeverMoveWhenTheListChanges() {
    var aliases = new ApprovalAliases();
    assertEquals("1", aliases.bind("project", "session", "first"));
    assertEquals("2", aliases.bind("project", "session", "second"));
    assertEquals("2", aliases.bind("project", "session", "second"));
    assertEquals("first", aliases.resolve("project", "session", "1"));
    assertEquals("second", aliases.resolve("project", "session", "2"));
    assertThrows(IllegalArgumentException.class, () -> aliases.resolve("other", "session", "1"));
    assertThrows(IllegalArgumentException.class, () -> aliases.resolve("project", "other", "1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ApprovalAliases().resolve("project", "session", "1"));
  }

  @Test
  void explicitContinueRoutesAsAnExplicitDecision() {
    var request =
        RemoteCommands.route(
            List.of("/approval", "approve", "request", "--continue"), "project", "session");
    assertEquals(Map.of("approved", true, "continueRun", true), request.body());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RemoteCommands.route(
                List.of("/approval", "deny", "request", "--continue"), "project", "session"));
  }
}
