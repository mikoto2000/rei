package dev.mikoto2000.rei.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 文書Draftエージェントの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class DocumentDraftAgentTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** {@code definition} の条件を再現するための補助情報を返す。 */
  SubAgentDefinition definition() {
    return new SubAgentDefinitionLoader(
            new SubAgentToolPolicy(
                Set.of("readMultiFile", "grepMultiQuery", "searchAndRead", "readPdfFile")),
            m -> false)
        .load(Path.of("config/subagents/document-editor.yaml"));
  }

  /** {@code envelope} の条件を再現するための補助情報を返す。 */
  tools.jackson.databind.node.ObjectNode envelope(String kind, String before, String after) {
    var json = tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode();
    json.put("status", "SUCCESS").put("summary", "Draft only; review required");
    json.putArray("warnings");
    var result = json.putObject("result");
    result
        .putArray("unverified")
        .add("Rendering and factual correctness require independent checks");
    var proposal = result.putObject("proposal");
    proposal
        .put("path", "document.txt")
        .put("format", kind)
        .put("expectedText", before)
        .put("replacement", after)
        .put("reason", "Requested revision");
    result.putArray("sources");
    return json;
  }

  /** 補助エージェントへの委譲で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void actualSampleIsReadOnlyBoundedAndValidatesSingleFileProposals() {
    var agent = definition();
    assertEquals("document-editor", agent.id());
    assertTrue(agent.maxSteps() <= 10);
    assertTrue(agent.timeout().compareTo(java.time.Duration.ofSeconds(120)) <= 0);
    assertFalse(agent.requestedTools().contains("applyTextChangeSet"));
    assertFalse(agent.requestedTools().contains("runCommand"));
    var validator = new SubAgentResultValidator();
    for (String format : List.of("TEXT", "MARKDOWN", "MERMAID", "PLANTUML"))
      assertTrue(validator.validate(agent, envelope(format, "before\r\n", "after\r\n")).valid());
    assertFalse(validator.validate(agent, envelope("DOCX", "before", "after")).valid());
    assertFalse(validator.validate(agent, envelope("TEXT", "", "after")).valid());
    var unknown = envelope("TEXT", "before", "after");
    ((tools.jackson.databind.node.ObjectNode) unknown.at("/result/proposal"))
        .put("command", "overwrite all files");
    assertFalse(validator.validate(agent, unknown).valid());
    var partial = envelope("TEXT", "before", "after");
    partial.put("status", "PARTIAL");
    ((tools.jackson.databind.node.ObjectNode) partial.get("result")).putNull("proposal");
    assertTrue(validator.validate(agent, partial).valid());
  }

  /** 補助エージェントへの委譲で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void validatedDraftPersistsWithoutWritingThenAppliesExactlyReviewedFileAndRejectsStaleSource()
      throws Exception {
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + root.resolve("drafts.db"));
    var repository = new dev.mikoto2000.rei.core.TextChangeSetRepository(source);
    var changes = new dev.mikoto2000.rei.core.TextChangeSetService(repository);
    var project =
        new dev.mikoto2000.rei.core.project.ProjectContext(
            UUID.randomUUID().toString(), "Project", root);
    String before = "graph TD\r\n  A-->B\r\n", after = "graph TD\r\n  A-->B\r\n  B-->C\r\n";
    var file = Files.writeString(root.resolve("document.txt"), before);
    var base = changes.readBase(project, "document.txt");
    var draft = envelope("MERMAID", base.text(), after);
    assertTrue(new SubAgentResultValidator().validate(definition(), draft).valid());
    var proposal = draft.at("/result/proposal");
    var saved =
        changes.propose(
            project,
            new dev.mikoto2000.rei.core.TextChangeSetService.Request(
                proposal.path("path").asString(),
                proposal.path("expectedText").asString(),
                proposal.path("replacement").asString()));
    assertEquals(before, Files.readString(file));
    assertEquals("PROPOSED", saved.status());
    assertTrue(saved.diff().contains("B-->C"));
    var restarted =
        new dev.mikoto2000.rei.core.TextChangeSetService(
            new dev.mikoto2000.rei.core.TextChangeSetRepository(source));
    var applied =
        restarted.apply(
            project,
            saved.id(),
            saved.proposalSha256(),
            (path, oldText, newText) -> Files.writeString(path, newText));
    assertEquals("APPLIED", applied.status());
    assertEquals(after, Files.readString(file));
    var stale =
        restarted.propose(
            project,
            new dev.mikoto2000.rei.core.TextChangeSetService.Request(
                "document.txt", after, before));
    Files.writeString(file, "concurrent user edit");
    assertEquals(
        "STALE",
        restarted
            .apply(
                project,
                stale.id(),
                stale.proposalSha256(),
                (p, o, n) -> fail("Must not overwrite changed source"))
            .status());
    assertEquals("concurrent user edit", Files.readString(file));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            restarted.propose(
                project,
                new dev.mikoto2000.rei.core.TextChangeSetService.Request(
                    "../outside", before, after)));
  }
}
