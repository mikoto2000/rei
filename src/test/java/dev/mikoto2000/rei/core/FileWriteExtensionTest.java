package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Partial batch, bounded large files and actionable failures through the existing APIs. */
class FileWriteExtensionTest {
  @TempDir Path root;
  private final com.fasterxml.jackson.databind.ObjectMapper json =
      new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

  private TextChangeSetRepository repository() {
    return new TextChangeSetRepository(
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            "jdbc:sqlite:" + root.resolve("changes.db")));
  }

  private AgentRunContext owner() {
    return new AgentRunContext("run", "session", root, "00000000-0000-0000-0000-000000000001");
  }

  private TextDocumentChangeSetService.Request partial(String text, String... paths) {
    return json.convertValue(
        Map.of(
            "operations",
            java.util.Arrays.stream(paths)
                .map(
                    path ->
                        Map.of(
                            "kind",
                            "UPDATE",
                            "path",
                            path,
                            "baseVersion",
                            TextDocumentTransaction.hash(text),
                            "edits",
                            List.of(Map.of("oldText", "old", "newText", "new"))))
                .toList()),
        TextDocumentChangeSetService.Request.class);
  }

  @Test
  void batchesPartialEditsAndKeepsDurableOneShotReceipt() throws Exception {
    String text = "\ufeffold\r\nunchanged\r\n";
    Files.writeString(root.resolve("a.txt"), text);
    Files.writeString(root.resolve("b.txt"), text);
    var service = new TextDocumentChangeSetService(repository(), Clock.systemUTC());
    var proposal = service.propose(owner(), partial(text, "a.txt", "b.txt"));
    assertEquals(text, Files.readString(root.resolve("a.txt")));
    var applied = service.apply(owner(), proposal.id(), proposal.proposalSha256());
    assertEquals("APPLIED", applied.status());
    assertTrue(applied.currentMatches());
    assertEquals(text.replace("old", "new"), Files.readString(root.resolve("b.txt")));
    Files.writeString(root.resolve("a.txt"), "external");
    assertFalse(service.apply(owner(), proposal.id(), proposal.proposalSha256()).currentMatches());
    assertEquals("external", Files.readString(root.resolve("a.txt")));
  }

  @Test
  void editsLargeFilesInBothServicesWithoutWholeFileInput() throws Exception {
    String text = "context\n".repeat(15000) + "old\n";
    Files.writeString(root.resolve("a.txt"), text);
    var repository = repository();
    var single = new TextChangeSetService(repository);
    var project = new ProjectContext("00000000-0000-0000-0000-000000000001", "test", root);
    var request =
        new TextChangeSetService.Request(
            "a.txt",
            null,
            null,
            TextDocumentTransaction.hash(text),
            List.of(new TextChangeSetService.Edit("old", "new")));
    var proposal = single.propose(project, request);
    assertEquals(
        "APPLIED",
        single
            .apply(
                project,
                proposal.id(),
                proposal.proposalSha256(),
                TextDocumentTransaction::writeSingle)
            .status());
    Files.writeString(root.resolve("a.txt"), text);
    var batch = new TextDocumentChangeSetService(repository, Clock.systemUTC());
    var multi = batch.propose(owner(), partial(text, "a.txt"));
    assertEquals("APPLIED", batch.apply(owner(), multi.id(), multi.proposalSha256()).status());
  }

  @Test
  void failuresAreStructuredWithoutEchoingSourceAndMixedModesAreRejected() throws Exception {
    String text = "secret-token-do-not-echo\naaaa\n";
    Files.writeString(root.resolve("a.txt"), text);
    var service = new TextChangeSetService(repository());
    var project = new ProjectContext("00000000-0000-0000-0000-000000000001", "test", root);
    for (var item :
        List.of(Map.entry("aaa", "AMBIGUOUS_MATCH"), Map.entry("missing", "TEXT_NOT_FOUND"))) {
      var failure =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  service.propose(
                      project,
                      new TextChangeSetService.Request(
                          "a.txt",
                          null,
                          null,
                          TextDocumentTransaction.hash(text),
                          List.of(new TextChangeSetService.Edit(item.getKey(), "x")))));
      assertTrue(failure.getMessage().contains(item.getValue()), failure.getMessage());
      assertFalse(failure.getMessage().contains("secret-token-do-not-echo"));
      var detail = json.readTree(failure.getMessage());
      assertEquals("a.txt", detail.path("path").asText());
      assertFalse(detail.path("safeToReplay").asBoolean(true));
    }
    var failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.propose(
                    project,
                    new TextChangeSetService.Request(
                        "a.txt",
                        null,
                        null,
                        "0".repeat(64),
                        List.of(new TextChangeSetService.Edit("aaaa", "x")))));
    assertTrue(failure.getMessage().contains("VERSION_MISMATCH"));
    var batch = new TextDocumentChangeSetService(repository(), Clock.systemUTC());
    var operation = new java.util.LinkedHashMap<String, Object>();
    operation.put("kind", "UPDATE");
    operation.put("path", "a.txt");
    operation.put("expectedText", text);
    operation.put("replacement", "x");
    operation.put("baseVersion", TextDocumentTransaction.hash(text));
    operation.put("edits", List.of(Map.of("oldText", "aaaa", "newText", "x")));
    var mixed =
        json.convertValue(
            Map.of("operations", List.of(operation)), TextDocumentChangeSetService.Request.class);
    assertThrows(IllegalArgumentException.class, () -> batch.propose(owner(), mixed));
    assertEquals(text, Files.readString(root.resolve("a.txt")));
  }
}
