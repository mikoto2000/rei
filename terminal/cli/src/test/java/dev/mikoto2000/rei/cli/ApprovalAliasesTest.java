package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/** 承認別名一覧の結果と状態の契約を検証するテスト。 */
class ApprovalAliasesTest {
  /** 軽量CLIでの対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
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

  /** 軽量CLIでの対話で、指定した条件に対する結果と状態の変化を確認する。 */
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
