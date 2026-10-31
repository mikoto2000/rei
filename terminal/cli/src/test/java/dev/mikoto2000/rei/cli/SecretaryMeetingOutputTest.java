package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class SecretaryMeetingOutputTest {
  @Test
  void humanProjectionOmitsLargeContextAndJsonIsExplicitlyAvailable() throws Exception {
    var response =
        new JsonMapper()
            .readTree(
                "{\"meeting\":{\"text\":\"朝ミーティング\"},\"context\":[{\"private\":\"large log\"}]}");
    assertEquals("朝ミーティング", SecretaryMeetingOutput.text(response));
    assertFalse(SecretaryMeetingOutput.text(response).contains("large log"));
    assertEquals(
        "/api/v1/secretary/review",
        RemoteCommands.route(List.of("/secretary", "review", "--json"), "p", null).path());
  }

  @Test
  void generatedReportAndConfirmationHaveHumanOutput() throws Exception {
    var json = new JsonMapper();
    assertEquals(
        "夕ミーティング",
        SecretaryMeetingOutput.text(
            json.readTree("{\"report\":{\"meeting\":{\"text\":\"夕ミーティング\"}}}")));
    assertNull(SecretaryMeetingOutput.text(json.readTree("{\"status\":\"unrelated\"}")));
  }

  @Test
  void explicitSelectionUsesAuthenticatedScopedMutationAndCanFinishEmpty() {
    var selected =
        RemoteCommands.route(
            List.of(
                "/secretary",
                "meeting-confirm",
                "--review",
                "--key",
                "evening",
                "--select",
                "run:a,run:b"),
            "p",
            null);
    assertEquals("POST", selected.method());
    assertEquals("/api/v1/secretary/meetings/confirm", selected.path());
    assertEquals(
        List.of("run:a", "run:b"), ((java.util.Map<?, ?>) selected.body()).get("selected"));
    assertEquals(
        List.of(),
        ((java.util.Map<?, ?>)
                RemoteCommands.route(
                        List.of("/secretary", "meeting-confirm", "--key", "end"), "p", null)
                    .body())
            .get("selected"));
  }
}
