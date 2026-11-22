package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ListingCommandsTest {
  @Test
  void completedHistoryUsesLimitAndGlobalScope() {
    assertEquals(
        "/api/v1/run-management/history?limit=3&status=completed",
        RemoteCommands.route(
                List.of("/runs", "history", "--status", "completed", "--limit", "3", "--all"),
                null,
                null)
            .path());
    assertEquals(
        "/api/v1/run-management/history?limit=20&projectId=one",
        RemoteCommands.route(List.of("/runs", "history"), "one", null).path());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RemoteCommands.route(List.of("/runs", "history", "--limit", "0", "--all"), null, null));
  }

  @Test
  void resumeAcrossProjectsUsesGlobalReadEndpoint() {
    var request =
        RemoteCommands.route(List.of("/resume", "--status", "failed", "--all"), null, null);
    assertEquals("GET", request.method());
    assertEquals("/api/v1/checkpoints/candidates?status=failed", request.path());
  }

  @Test
  void scopedResumeKeepsProjectAndRejectsInvalidStatus() {
    assertEquals(
        "/api/v1/checkpoints/candidates?projectId=one&status=failed",
        RemoteCommands.route(List.of("/resume", "--status", "failed"), "one", null).path());
    assertThrows(
        IllegalArgumentException.class,
        () -> RemoteCommands.route(List.of("/resume", "--status", "faild", "--all"), "one", null));
  }
}
