package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.project.ProjectContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Distant hunks, bounded complete retrieval and verified compact application receipts. */
class WriteDiffResultTest {
  @TempDir Path root;
  private final com.fasterxml.jackson.databind.ObjectMapper json =
      new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

  private TextChangeSetService service() {
    return new TextChangeSetService(new TextChangeSetRepository(
        new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:" + root.resolve("db"))));
  }

  private ProjectContext project() { return new ProjectContext("00000000-0000-0000-0000-000000000001", "test", root); }

  @Test
  void separatesDistantHunksAndApplyOmitsDiffButReturnsVerifiedRanges() throws Exception {
    String before = java.util.stream.IntStream.range(0, 500).mapToObj(i -> "line " + i + "\n").collect(java.util.stream.Collectors.joining());
    Files.writeString(root.resolve("a.txt"), before);
    var service = service();
    var proposal = service.propose(project(), new TextChangeSetService.Request("a.txt", null, null,
        TextDocumentTransaction.hash(before), List.of(new TextChangeSetService.Edit("line 20\n", "new 20\n"),
            new TextChangeSetService.Edit("line 480\n", "new 480\n"))));
    assertEquals(2, proposal.diff().lines().filter(line -> line.startsWith("@@ ")).count());
    assertFalse(proposal.diff().contains("line 250"));
    assertTrue(proposal.diff().contains(" line 19\n"));
    var applied = service.apply(project(), proposal.id(), proposal.proposalSha256(), TextDocumentTransaction::writeSingle);
    assertEquals("", applied.diff());
    var receipt = json.valueToTree(applied);
    assertTrue(receipt.path("currentMatches").asBoolean());
    assertEquals(2, receipt.path("changeCount").asInt());
    assertEquals(2, receipt.path("changes").get(0).path("ranges").size());
    assertEquals(TextDocumentTransaction.hash(Files.readString(root.resolve("a.txt"))), applied.currentSha256());
  }

  @Test
  void previewIsBoundedAndPagesRecoverCompleteDiffWithoutChangingProposalOrFiles() throws Exception {
    String before = "old\n";
    String replacement = "日本語😀".repeat(4000) + "\n";
    Files.writeString(root.resolve("a.txt"), before);
    var service = service();
    var proposal = service.propose(project(), new TextChangeSetService.Request("a.txt", before, replacement));
    assertTrue(proposal.diff().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 16384);
    var preview = json.valueToTree(proposal).path("diffInfo");
    assertTrue(preview.path("truncated").asBoolean());
    assertTrue(preview.path("omittedBytes").asInt() > 0);
    assertTrue(preview.path("omissions").size() > 0);
    var method = TextChangeSetService.class.getMethod("diffPage", ProjectContext.class, String.class, String.class, int.class, int.class);
    var recovered = new StringBuilder();
    int offset = 0;
    do {
      var page = json.valueToTree(method.invoke(service, project(), proposal.id(), proposal.proposalSha256(), offset, 1024));
      String text = page.path("diff").asText();
      assertTrue(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1024);
      assertFalse(text.contains("\ufffd"));
      recovered.append(text);
      offset = page.path("info").path("nextOffset").asInt(-1);
    } while (offset >= 0);
    assertTrue(recovered.toString().contains("+" + replacement));
    assertEquals(before, Files.readString(root.resolve("a.txt")));
    assertThrows(java.lang.reflect.InvocationTargetException.class, () -> method.invoke(service, project(), proposal.id(), "wrong-sha", 0, 1024));
  }
}
