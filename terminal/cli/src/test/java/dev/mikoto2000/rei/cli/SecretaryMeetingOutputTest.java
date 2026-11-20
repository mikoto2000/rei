package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.mikoto2000.rei.core.completion.MeetingSelectionMenu;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Size;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.Test;

class SecretaryMeetingOutputTest {
  @Test
  void arrowKeysMoveAndSpaceTogglesWithoutChangingTheOrdinaryReader() throws Exception {
    var candidates =
        List.of(
            new MeetingSelectionMenu.Candidate("p::a", "作業A"),
            new MeetingSelectionMenu.Candidate("p::b", "作業B"),
            new MeetingSelectionMenu.Candidate("p::c", "作業C"),
            new MeetingSelectionMenu.Candidate("p::d", "作業D"));
    try (var terminal =
        new DumbTerminal(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())) {
      terminal.setSize(new Size(80, 10));
      var reader = LineReaderBuilder.builder().terminal(terminal).build();
      var attributes = terminal.getAttributes();
      var keymap = reader.getKeyMaps().get("main");
      // Select three; reject the fourth; deselect the first; select the fourth instead.
      reader.runMacro(" \033[B \033[B \033[B \033[B \033[A \r");
      assertEquals(
          List.of("p::b", "p::c", "p::d"),
          MeetingSelectionMenu.choose(reader, candidates).orElseThrow());
      assertEquals(attributes.toString(), terminal.getAttributes().toString());
      assertSame(keymap, reader.getKeyMaps().get("main"));
      reader.runMacro("\r");
      assertEquals(List.of(), MeetingSelectionMenu.choose(reader, candidates).orElseThrow());
      for (String cancel : List.of("\033", "\003", "\004")) {
        reader.runMacro(" " + cancel);
        assertTrue(MeetingSelectionMenu.choose(reader, candidates).isEmpty());
      }
    }
  }

  @Test
  void selectionUsesStructuredIdsAndOnlyCurrentUnconfirmedCandidates() throws Exception {
    var report =
        new JsonMapper()
            .readTree(
                """
        {"date":"2026-11-20","review":true,"meeting":{"question":"選んでください",
         "confirmed":false,"sections":{"carryover":[
           {"projectId":"p","id":"run:r","title":"調査","state":"UNKNOWN"},
           {"projectId":"p","id":"run:r","title":"重複","state":"UNKNOWN"}],
           "completed":[{"projectId":"p","id":"run:done"}]}}}
        """);
    var candidates = MeetingSelectionMenu.candidates(report);
    assertEquals(1, candidates.size());
    assertEquals("p::run:r", candidates.getFirst().id());
    assertTrue(candidates.getFirst().label().contains("状態未検証"));
    var global =
        SecretaryMeetingOutput.confirmation(
            report, "/api/v1/secretary/review", "key", List.of("p::run:r"));
    assertFalse(global.containsKey("projectId"));
    assertEquals("2026-11-20", global.get("date"));
    assertEquals(true, global.get("review"));
    var local =
        SecretaryMeetingOutput.confirmation(
            report, "/api/v1/secretary/review?projectId=p%3Aone&date=2026-11-20", "key", List.of());
    assertEquals("p:one", local.get("projectId"));
    ((com.fasterxml.jackson.databind.node.ObjectNode) report.path("meeting"))
        .put("confirmed", true);
    assertTrue(MeetingSelectionMenu.candidates(report).isEmpty());
  }

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
