package dev.mikoto2000.rei.core.stagnation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.core.TextChangeSetRepository;
import dev.mikoto2000.rei.core.TextChangeSetService;
import dev.mikoto2000.rei.core.TextDiff;
import dev.mikoto2000.rei.core.TextDocumentChangeSetService;
import dev.mikoto2000.rei.core.TextDocumentTransaction;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class EditPreparationProgressTest {
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  @TempDir Path root;

  @Test
  void exactBaselineIsNewAcquisitionOncePerPathAndVersionIncludingAfterResume() throws Exception {
    String text = "\uFEFFfirst\r\nsecond\r\n";
    Files.writeString(root.resolve("A.md"), text);
    var evaluator = new ProgressEvaluator(root);
    String result =
        JSON.writeValueAsString(
            new TextChangeSetService.Baseline("A.md", text, TextDocumentTransaction.hash(text)));
    assertThat(observe(evaluator, "readTextChangeSetBase", result))
        .singleElement()
        .satisfies(
            evidence -> {
              assertThat(evidence.kind()).isEqualTo(ProgressEvent.NEW_INFORMATION);
              assertThat(evidence.acquisitionProgress()).isTrue();
            });
    assertThat(evaluator.readContentObserved()).isTrue();
    assertThat(observe(evaluator, "readTextChangeSetBase", result)).isEmpty();
    var restored = new ProgressEvaluator(root);
    restored.restoreInformation(evaluator.informationSnapshot());
    assertThat(observe(restored, "readTextChangeSetBase", result)).isEmpty();
    restored.beginContextRestoration();
    assertThat(observe(restored, "readTextChangeSetBase", result))
        .extracting(ProgressEvidence::kind)
        .containsExactly(ProgressEvent.CONTEXT_RESTORED);
    assertThat(observe(restored, "readTextChangeSetBase", result)).isEmpty();
    assertThat(restored.snapshot().restorationReads()).isEqualTo(1);
    String edited = "changed\n";
    Files.writeString(root.resolve("A.md"), edited);
    assertThat(
            observe(
                restored,
                "readTextChangeSetBase",
                JSON.writeValueAsString(
                    new TextChangeSetService.Baseline(
                        "./A.md", edited, TextDocumentTransaction.hash(edited)))))
        .hasSize(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "wrongHash", "stale", "outside", "failure"})
  void invalidBaselineDoesNotCount(String defect) throws Exception {
    Files.writeString(root.resolve("A.md"), "text");
    var result =
        JSON.valueToTree(
            new TextChangeSetService.Baseline(
                "A.md", "text", TextDocumentTransaction.hash("text")));
    var node = (com.fasterxml.jackson.databind.node.ObjectNode) result;
    switch (defect) {
      case "missing" -> node.remove("text");
      case "wrongHash" -> node.put("sha256", "0".repeat(64));
      case "stale" -> Files.writeString(root.resolve("A.md"), "external edit");
      case "outside" -> node.put("path", "../outside.md");
      case "failure" -> node.put("error", "failed");
      default -> throw new AssertionError(defect);
    }
    assertThat(observe(new ProgressEvaluator(root), "readTextChangeSetBase", node.toString()))
        .isEmpty();
  }

  @Test
  void realReadProposeReviewApplyWorkflowDoesNotStagnateBeforeApply() throws Exception {
    var repository =
        new TextChangeSetRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + root.resolve("changes.db")));
    var single = new TextChangeSetService(repository);
    var documents = new TextDocumentChangeSetService(repository, Clock.systemUTC());
    var project = new ProjectContext(UUID.randomUUID().toString(), "test", root);
    var owner = new AgentRunContext("run", "session", root, project.id());
    var plan = new dev.mikoto2000.rei.core.actionplan.ActionPlan();
    plan.addStep("edit documents");
    var evaluator = new ProgressEvaluator(root, plan);
    var budget = new OutputLimitRunBudget(2, 100);
    assertThat(budget.tryConsumeStagnationReplan()).isTrue();
    assertThat(budget.tryConsumeStagnationReplan()).isTrue();
    var context =
        new RunExecutionContext(
            "run", budget, evaluator, new AgentEventFactory(Clock.systemUTC()), event -> {});
    for (int i = 0; i < 3; i++) {
      context.beginIteration();
      context.endIteration();
    }
    var operations = new ArrayList<TextDocumentChangeSetService.Operation>();
    for (int i = 0; i < 4; i++) {
      String path = "file-" + i + ".md";
      Files.writeString(root.resolve(path), "old\r\n");
      iteration(context, "readTextChangeSetBase", single.readBase(project, path));
      assertThat(context.detector().stagnationCount()).isZero();
      // A provider timeout/empty iteration must not erase the previous acquisition.
      if (i == 1) {
        context.beginIteration();
        context.endIteration();
      }
      operations.add(
          new TextDocumentChangeSetService.Operation("UPDATE", path, null, "old\r\n", "new\r\n"));
    }
    var proposal = documents.propose(owner, new TextDocumentChangeSetService.Request(operations));
    iteration(context, "proposeTextDocumentChangeSet", proposal);
    assertThat(context.detector().stagnationCount()).isZero();
    assertThat(plan.steps()).noneMatch(step -> "DONE".equals(step.status()));
    assertThat(root.resolve("file-0.md")).hasContent("old\r\n");
    assertThat(observe(evaluator, "inspectTextDocumentChangeSet", proposalJson(proposal)))
        .isEmpty();
    var applied = documents.apply(owner, proposal.id(), proposal.proposalSha256());
    iteration(context, "applyTextDocumentChangeSet", applied);
    assertThat(context.detector().stagnationCount()).isZero();
    assertThat(root.resolve("file-0.md")).hasContent("new\r\n");
    assertThat(budget.snapshot().stagnationReplans()).isEqualTo(2);
  }

  @ParameterizedTest
  @ValueSource(strings = {"proposeTextChangeSet", "proposeTextDocumentChangeSet"})
  void proposalDiffCountsOnceWithoutTreatingItAsAppliedWork(String tool) throws Exception {
    Files.writeString(root.resolve("A.md"), "old");
    var repository =
        new TextChangeSetRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + root.resolve("changes.db")));
    var project = new ProjectContext(UUID.randomUUID().toString(), "test", root);
    var single = new TextChangeSetService(repository);
    var documents = new TextDocumentChangeSetService(repository, Clock.systemUTC());
    var owner = new AgentRunContext("run", "session", root, project.id());
    var evaluator = new ProgressEvaluator(root);
    Object first =
        tool.equals("proposeTextChangeSet")
            ? single.propose(project, new TextChangeSetService.Request("A.md", "old", "new"))
            : documents.propose(
                owner,
                new TextDocumentChangeSetService.Request(
                    List.of(
                        new TextDocumentChangeSetService.Operation(
                            "UPDATE", "A.md", null, "old", "new"))));
    assertThat(observe(evaluator, tool, JSON.writeValueAsString(first)))
        .singleElement()
        .satisfies(e -> assertThat(e.kind()).isEqualTo(ProgressEvent.NEW_INFORMATION));
    Object second =
        tool.equals("proposeTextChangeSet")
            ? single.propose(project, new TextChangeSetService.Request("A.md", "old", "new"))
            : documents.propose(
                owner,
                new TextDocumentChangeSetService.Request(
                    List.of(
                        new TextDocumentChangeSetService.Operation(
                            "UPDATE", "A.md", null, "old", "new"))));
    assertThat(observe(evaluator, tool, JSON.writeValueAsString(second))).isEmpty();
    var restored = new ProgressEvaluator(root);
    restored.restoreInformation(evaluator.informationSnapshot());
    assertThat(observe(restored, tool, JSON.writeValueAsString(second))).isEmpty();
    for (String status : List.of("UNKNOWN", "FAILED", "DISCARDED")) {
      var invalid = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.valueToTree(first);
      invalid.put("status", status);
      assertThat(observe(new ProgressEvaluator(root), tool, invalid.toString())).isEmpty();
    }
    var noDiff = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.valueToTree(first);
    noDiff.put("diff", "");
    assertThat(observe(new ProgressEvaluator(root), tool, noDiff.toString())).isEmpty();
  }

  @Test
  void diffPagesDeduplicateAcrossReadersAndRejectFailuresOrInvalidMetadata() throws Exception {
    String diff = "--- A\n+++ A\n@@ -1 +1 @@\n-old\n+new\n";
    var page =
        JSON.valueToTree(
            new TextDiff.Page(
                diff,
                new TextDiff.Info(diff.length(), diff.length(), false, -1, 0, List.of(), "")));
    var evaluator = new ProgressEvaluator(root);
    assertThat(observe(evaluator, "getTextDocumentChangeSetDiff", page.toString())).hasSize(1);
    assertThat(observe(evaluator, "getTextChangeSetDiff", page.toString())).isEmpty();
    var invalid = (com.fasterxml.jackson.databind.node.ObjectNode) page.deepCopy();
    invalid.withObject("info").put("returnedBytes", 999);
    assertThat(observe(new ProgressEvaluator(root), "getTextChangeSetDiff", invalid.toString()))
        .isEmpty();
    invalid = (com.fasterxml.jackson.databind.node.ObjectNode) page.deepCopy();
    invalid.put("error", "failed");
    assertThat(observe(new ProgressEvaluator(root), "getTextChangeSetDiff", invalid.toString()))
        .isEmpty();
  }

  private String proposalJson(Object proposal) throws Exception {
    return JSON.writeValueAsString(proposal);
  }

  private void iteration(RunExecutionContext context, String tool, Object result) throws Exception {
    context.beginIteration();
    context.recordTool(
        tool, "{}", JSON.writeValueAsString(result), context.evaluator().beforeTool(tool, "{}"));
    context.endIteration();
  }

  private List<ProgressEvidence> observe(ProgressEvaluator evaluator, String tool, String result) {
    return evaluator.afterTool(tool, "{}", result, evaluator.beforeTool(tool, "{}"));
  }
}
