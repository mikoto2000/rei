package dev.mikoto2000.rei.dot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class DotCommandRequestTest {
  @Test
  void requestOptionsAreSharedAndBounded() {
    var r =
        DotCommandRequest.parse(
            List.of(
                "request",
                "Compare options",
                "--project-id",
                "project",
                "--category",
                "COMPARISON",
                "--context",
                "Background",
                "--expected-output",
                "Report",
                "--constraint",
                "read only",
                "--acceptance-criterion",
                "sources",
                "--timeout",
                "3600"));
    assertEquals("request", r.action());
    assertEquals("project", r.projectId());
    assertEquals("Compare options", r.payload().get("instruction"));
    assertEquals(List.of("read only"), r.payload().get("constraints"));
    assertEquals(3600, r.payload().get("timeout"));
  }

  @Test
  void fixedCommandsAndUnknownOptions() {
    assertEquals("abc", DotCommandRequest.parse(List.of("cancel", "abc")).requestId());
    assertEquals("requests", DotCommandRequest.parse(List.of("requests")).action());
    assertThrows(
        IllegalArgumentException.class,
        () -> DotCommandRequest.parse(List.of("request", "task", "--shell", "rm")));
    assertThrows(IllegalArgumentException.class, () -> DotCommandRequest.parse(List.of("request")));
    assertThrows(
        IllegalArgumentException.class,
        () -> DotCommandRequest.parse(List.of("status", "id", "extra")));
  }
}
