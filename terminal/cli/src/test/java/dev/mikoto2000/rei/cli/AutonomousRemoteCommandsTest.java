package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AutonomousRemoteCommandsTest {
  private RemoteCommands.Request route(String... words) {
    return RemoteCommands.route(List.of(words), "private-project", "private-session");
  }

  @Test
  void draftReviewIsGlobalAndHoldIsReadOnly() {
    assertTrue(RemoteCommands.NAMES.contains("/autonomous"));
    assertEquals("/api/v1/autonomous/sources?limit=20", route("/autonomous").path());
    assertEquals(
        "/api/v1/autonomous/drafts?limit=10",
        route("/autonomous", "drafts", "--limit", "10").path());
    assertEquals("/api/v1/autonomous/drafts/id", route("/autonomous", "draft", "id").path());
    var hold = route("/autonomous", "hold", "id");
    assertEquals("GET", hold.method());
    assertEquals("/api/v1/autonomous/drafts/id", hold.path());
    assertNull(hold.body());
    assertEquals(
        "/api/v1/autonomous/drafts?limit=20",
        RemoteCommands.route(List.of("/autonomous", "drafts"), null, null).path());
  }

  @Test
  void editsPreserveTheExactTextAndRequireTheReviewedRevision() {
    var text = "全文\r\n次の行";
    var edit = route("/autonomous", "edit", "id", "--revision", "2", "--text", text);
    assertEquals("POST", edit.method());
    assertEquals("/api/v1/autonomous/drafts/id/edit", edit.path());
    assertEquals(Map.of("expectedRevision", 2, "text", text), edit.body());
    assertEquals(Duration.ofSeconds(30), edit.mutationTimeout());
    assertThrows(
        IllegalArgumentException.class, () -> route("/autonomous", "edit", "id", "--text", text));
    assertThrows(
        IllegalArgumentException.class,
        () -> route("/autonomous", "edit", "id", "--revision", "0", "--text", text));
  }

  @Test
  void generationBudgetsIncludeTheBoundedFallbackWithoutChangingOtherCommands() {
    var generated = route("/autonomous", "generate");
    assertEquals("/api/v1/autonomous/drafts/generate", generated.path());
    assertEquals(Map.of(), generated.body());
    assertEquals(Duration.ofSeconds(180), generated.mutationTimeout());
    var regenerated = route("/autonomous", "regenerate", "id", "--revision", "3");
    assertEquals("/api/v1/autonomous/drafts/id/regenerate", regenerated.path());
    assertEquals(Map.of("expectedRevision", 3), regenerated.body());
    assertEquals(Duration.ofSeconds(180), regenerated.mutationTimeout());
    assertEquals(
        Duration.ofSeconds(30),
        route("/autonomous", "reject", "id", "--revision", "3").mutationTimeout());
  }

  @Test
  void rejectionReasonIsOptionalAndNeverGrantsPublicUse() {
    assertEquals(
        Map.of("expectedRevision", 1, "reason", ""),
        route("/autonomous", "reject", "id", "--revision", "1").body());
    var rejected =
        route("/autonomous", "reject", "id", "--revision", "1", "--reason", "PRIVATE_REASON");
    assertEquals(Map.of("expectedRevision", 1, "reason", "PRIVATE_REASON"), rejected.body());
    assertThrows(
        IllegalArgumentException.class,
        () -> route("/autonomous", "reject", "id", "--revision", "1", "--public-use"));
  }

  @Test
  void topicPermissionChangesAreExplicitAndRevisionChecked() {
    assertEquals(
        Map.of("text", "話題", "publicUseAllowed", false),
        route("/autonomous", "add-source", "--text", "話題").body());
    assertEquals(
        Map.of("text", "話題", "publicUseAllowed", true),
        route("/autonomous", "add-source", "--text", "話題", "--public-use").body());
    var edited = route("/autonomous", "edit-source", "id", "--revision", "2", "--text", "新しい話題");
    assertEquals("/api/v1/autonomous/sources/id/edit", edited.path());
    assertEquals(Map.of("expectedRevision", 2, "text", "新しい話題"), edited.body());
    assertEquals(
        "/api/v1/autonomous/sources/id/permit",
        route("/autonomous", "permit-source", "id", "--revision", "3").path());
    assertEquals(
        "/api/v1/autonomous/sources/id/revoke",
        route("/autonomous", "revoke-source", "id", "--revision", "4").path());
    assertThrows(IllegalArgumentException.class, () -> route("/autonomous", "permit-source", "id"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            route(
                "/autonomous",
                "edit-source",
                "id",
                "--revision",
                "1",
                "--text",
                "話題",
                "--public-use"));
  }

  @Test
  void unknownPublicationCommandsAndAuthorityOptionsNeverBecomeRequests() {
    for (var action : List.of("publish", "approve", "authorize", "shell", "../publish"))
      assertThrows(IllegalArgumentException.class, () -> route("/autonomous", action, "id"));
    assertThrows(
        IllegalArgumentException.class,
        () -> route("/autonomous", "regenerate", "id", "--revision", "1", "--approved"));
    assertThrows(
        IllegalArgumentException.class, () -> route("/autonomous", "drafts", "--limit", "201"));
    assertThrows(IllegalArgumentException.class, () -> route("/autonomous", "draft", ".."));
  }
}
