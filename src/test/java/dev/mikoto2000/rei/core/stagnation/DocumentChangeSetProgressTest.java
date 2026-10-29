package dev.mikoto2000.rei.core.stagnation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mikoto2000.rei.core.TextChangeSetRepository;
import dev.mikoto2000.rei.core.TextDocumentChangeSetService;
import dev.mikoto2000.rei.core.TextDocumentTransaction;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DocumentChangeSetProgressTest {
  private static final String TOOL = "applyTextDocumentChangeSet";
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  @TempDir Path root;

  @Test
  void actualMultiFileApplyResetsStagnationAndDeduplicatesAcrossResume() throws Exception {
    Files.writeString(root.resolve("A.md"), "old");
    Files.writeString(root.resolve("B.md"), "old");
    var service =
        new TextDocumentChangeSetService(
            new TextChangeSetRepository(
                new DriverManagerDataSource("jdbc:sqlite:" + root.resolve("changes.db"))),
            Clock.systemUTC());
    var owner = new AgentRunContext("run", "session", root, "project");
    var proposal =
        service.propose(
            owner,
            new TextDocumentChangeSetService.Request(
                List.of(
                    new TextDocumentChangeSetService.Operation(
                        "UPDATE", "A.md", null, "old", "new"),
                    new TextDocumentChangeSetService.Operation(
                        "UPDATE", "B.md", null, "old", "new"))));
    var evaluator = new ProgressEvaluator(root);
    var context =
        new RunExecutionContext(
            "run",
            new OutputLimitRunBudget(2, 100),
            evaluator,
            new AgentEventFactory(Clock.systemUTC()),
            event -> {});
    for (int i = 0; i < 3; i++) {
      context.beginIteration();
      context.endIteration();
    }
    assertThat(context.detector().stagnationCount()).isEqualTo(3);
    String args =
        JSON.writeValueAsString(
            java.util.Map.of("id", proposal.id(), "proposalSha256", proposal.proposalSha256()));
    context.beginIteration();
    var before = evaluator.beforeTool(TOOL, args);
    var receipt = service.apply(owner, proposal.id(), proposal.proposalSha256());
    String result = JSON.writeValueAsString(receipt);
    context.recordTool(TOOL, args, result, before);
    context.endIteration();
    assertThat(context.detector().stagnationCount()).isZero();
    assertThat(context.progressVersion()).isPositive();
    assertThat(observe(evaluator, result)).isEmpty();
    var restored = new ProgressEvaluator(root);
    restored.restore(evaluator.snapshot());
    assertThat(
            observe(
                restored,
                JSON.writeValueAsString(
                    service.apply(owner, proposal.id(), proposal.proposalSha256()))))
        .isEmpty();
  }

  @Test
  void createDeleteAndRenameExistenceChangesCountButNoOpsDoNot() throws Exception {
    Files.writeString(root.resolve("created.md"), "new");
    Files.writeString(root.resolve("renamed.md"), "old");
    Files.writeString(root.resolve("same.md"), "same");
    var result = receipt();
    result.withArray("files").add(file("created.md", null, "new"));
    result.withArray("files").add(file("deleted.md", "old", null));
    result.withArray("files").add(file("original.md", "old", null));
    result.withArray("files").add(file("renamed.md", null, "old"));
    result.withArray("files").add(file("same.md", "same", "same"));
    var evidence = observe(new ProgressEvaluator(root), result.toString());
    assertThat(evidence)
        .hasSize(4)
        .allSatisfy(entry -> assertThat(entry.kind()).isEqualTo(ProgressEvent.STATE_CHANGED));
    assertThat(evidence)
        .extracting(ProgressEvidence::revision)
        .contains("absent", TextDocumentTransaction.hash("new"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"PROPOSED", "UNKNOWN", "ROLLED_BACK", "FAILED"})
  void uncommittedReceiptsDoNotCount(String status) throws Exception {
    Files.writeString(root.resolve("A.md"), "new");
    var result = receipt();
    result.put("status", status);
    result.withArray("files").add(file("A.md", "old", "new"));
    assertThat(observe(new ProgressEvaluator(root), result.toString())).isEmpty();
  }

  @Test
  void staleUnavailableAndOutOfRootReceiptsDoNotCount() throws Exception {
    Files.writeString(root.resolve("A.md"), "external edit");
    var result = receipt();
    var file = file("A.md", "old", "new");
    result.withArray("files").add(file);
    assertThat(observe(new ProgressEvaluator(root), result.toString())).isEmpty();
    Files.writeString(root.resolve("A.md"), "new");
    result.put("currentMatches", false);
    assertThat(observe(new ProgressEvaluator(root), result.toString())).isEmpty();
    result.put("currentMatches", true);
    file.put("currentAvailable", false);
    assertThat(observe(new ProgressEvaluator(root), result.toString())).isEmpty();
    file.put("currentAvailable", true);
    file.put("path", "../outside.md");
    assertThat(observe(new ProgressEvaluator(root), result.toString())).isEmpty();
  }

  private ObjectNode receipt() {
    return JSON.createObjectNode().put("status", "APPLIED").put("currentMatches", true);
  }

  @Test
  void noOpAndPreviouslyObservedWriteDoNotCount() throws Exception {
    Files.writeString(root.resolve("A.md"), "old");
    var evaluator = new ProgressEvaluator(root);
    var result = receipt();
    result.withArray("files").add(file("A.md", "old", "old"));
    assertThat(observe(evaluator, result.toString())).isEmpty();
    String args = "{\"path\":\"A.md\"}";
    var before = evaluator.beforeTool("writeTextFile", args);
    Files.writeString(root.resolve("A.md"), "new");
    assertThat(evaluator.afterTool("writeTextFile", args, "{}", before)).hasSize(1);
    result.withArray("files").removeAll().add(file("A.md", "old", "new"));
    assertThat(observe(evaluator, result.toString())).isEmpty();
  }

  @Test
  void staleEndpointPreventsPartialCreditAndDoesNotConsumeValidEvidence() throws Exception {
    Files.writeString(root.resolve("A.md"), "new");
    Files.writeString(root.resolve("B.md"), "external");
    var evaluator = new ProgressEvaluator(root);
    var result = receipt();
    result.withArray("files").add(file("A.md", "old", "new"));
    result.withArray("files").add(file("B.md", "old", "new"));
    assertThat(observe(evaluator, result.toString())).isEmpty();
    Files.writeString(root.resolve("B.md"), "new");
    assertThat(observe(evaluator, result.toString())).hasSize(2);
  }

  private ObjectNode file(String path, String before, String after) {
    var node = JSON.createObjectNode().put("path", path);
    node.put("baselineExists", before != null);
    node.put("baselineSha256", TextDocumentTransaction.hash(before));
    node.put("proposedExists", after != null);
    node.put("proposedSha256", TextDocumentTransaction.hash(after));
    node.put("currentAvailable", true);
    node.put("currentExists", after != null);
    node.put("currentSha256", TextDocumentTransaction.hash(after));
    return node;
  }

  private List<ProgressEvidence> observe(ProgressEvaluator evaluator, String result) {
    return evaluator.afterTool(TOOL, "{}", result, evaluator.beforeTool(TOOL, "{}"));
  }
}
