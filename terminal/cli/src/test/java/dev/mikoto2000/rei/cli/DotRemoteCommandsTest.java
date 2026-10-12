package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class DotRemoteCommandsTest {
  @Test
  void dotCommandsUseFixedDomainApiWithoutShellForwarding() {
    var request =
        RemoteCommands.route(
            List.of("/dot", "request", "Compare", "--context", "background", "--timeout", "60"),
            "project",
            "session");
    assertEquals("POST", request.method());
    assertEquals("/api/v1/projects/project/dot-requests", request.path());
    assertEquals("session", ((Map<?, ?>) request.body()).get("session_id"));
    assertEquals(
        "GET",
        RemoteCommands.route(List.of("/dot", "result", "request"), "project", "session").method());
    assertEquals(
        "/api/v1/projects/project/dot-requests/request/cancel",
        RemoteCommands.route(List.of("/dot", "cancel", "request"), "project", "session").path());
    assertEquals(
        "/api/v1/projects/project/dot-requests/request/verify",
        RemoteCommands.route(
                List.of(
                    "/dot",
                    "verify",
                    "request",
                    "--status",
                    "VERIFIED",
                    "--satisfied",
                    "0",
                    "--note",
                    "checked",
                    "--yes"),
                "project",
                "session")
            .path());
    var resume =
        RemoteCommands.route(List.of("/dot", "resume", "request", "--yes"), "project", "session");
    assertEquals("/api/v1/projects/project/dot-requests/request/resume", resume.path());
    assertEquals(Map.of("confirmed", true), resume.body());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RemoteCommands.route(
                List.of("/dot", "request", "task", "--shell", "cmd"), "project", "session"));
  }
}
