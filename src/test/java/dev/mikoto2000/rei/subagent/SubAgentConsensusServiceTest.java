package dev.mikoto2000.rei.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** 補助エージェント評価の合意サービスの結果と状態の契約を検証するテスト。 */
class SubAgentConsensusServiceTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path temporary;

  /** 検証に使う保存と検索の窓口。 */
  DurableSubAgentRepository repository;

  /** 検証に使う実行の担当。 */
  SubAgentRunner runner;

  /** 検証に使う登録台帳。 */
  SubAgentRegistry registry;

  /** 検証に使うこの機能の動作設定。 */
  SubAgentProperties properties;

  /** 検証に使う所有者。 */
  AgentRunContext owner;

  /** 検証に使うサービス。 */
  SubAgentConsensusService service;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("project")),
        config = Files.createDirectory(temporary.resolve("config"));
    owner = new AgentRunContext("parent", "session", root, "project");
    var source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + temporary.resolve("children.db"));
    repository = new DurableSubAgentRepository(source, Clock.systemUTC());
    Files.writeString(
        config.resolve("reviewer.yaml"),
        SubAgentConfigurationTest.yaml("reviewer") + "evidenceTools: [readMultiFile]\n");
    Files.writeString(
        config.resolve("judge.yaml"),
        SubAgentConfigurationTest.yaml("judge").replace("tools: [readMultiFile]", "tools: []"));
    registry =
        new SubAgentRegistry(
            config,
            new SubAgentDefinitionLoader(
                new SubAgentToolPolicy(Set.of("readMultiFile")), m -> true));
    assertTrue(registry.reload().isEmpty());
    runner = mock(SubAgentRunner.class);
    when(runner.durableBaseline(any(), eq(owner))).thenReturn("baseline");
    properties = new SubAgentProperties();
    properties.setDurableEnabled(true);
    properties.setConsensusEnabled(true);
    service = new SubAgentConsensusService(properties, repository, runner, registry);
  }

  /** {@code answer} の条件を再現するための補助情報を返す。 */
  SubAgentConsensusService.Reference answer(String text, String hash, boolean evidence) {
    var child = repository.create(owner, "reviewer", "same question", null, 2, 0, "baseline");
    String run = UUID.randomUUID().toString();
    repository.claim(owner, child.id(), 0, run);
    String output =
        "{\"status\":\"SUCCESS\",\"summary\":\"answer\",\"result\":{\"answer\":\""
            + text
            + "\",\"evidence\":"
            + (evidence
                ? "[{\"evidenceId\":\"e-"
                    + run
                    + "\",\"tool\":\"readMultiFile\",\"outputSha256\":\""
                    + hash
                    + "\",\"quote\":\"observed\"}]"
                : "[]")
            + "},\"warnings\":[]}";
    repository.complete(child.id(), run, "COMPLETED", output);
    return new SubAgentConsensusService.Reference(
        child.id(), repository.get(owner, child.id()).resultHash());
  }

  /** 補助エージェントへの委譲で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void majorityDoesNotEraseDisagreementOrProvenance() {
    var refs =
        List.of(
            answer("yes", "a".repeat(64), true),
            answer("yes", "a".repeat(64), true),
            answer("no", "b".repeat(64), true));
    var compared = service.compare(owner, refs);
    assertEquals("DISAGREEMENT", compared.status());
    assertTrue(compared.unresolved());
    assertFalse(compared.truthVerified());
    assertEquals(3, compared.sources().size());
    assertEquals(2, compared.answers().size());
    assertEquals(1, compared.sharedEvidenceHashes().size());
    assertEquals(refs.getFirst().resultHash(), compared.sources().getFirst().resultHash());
    verifyNoInteractionsExceptBaseline();
  }

  /** 検証用に、要求された条件を満たすか判断するため、なし対話一覧Except比較の基準を確認する。 */
  void verifyNoInteractionsExceptBaseline() {
    verify(runner, never()).run(anyString(), anyString(), any(), any());
  }

  /** 補助エージェントへの委譲で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void agreementWithoutEvidenceRemainsUnresolved() {
    var compared =
        service.compare(
            owner,
            List.of(answer("same", "a".repeat(64), false), answer("same", "b".repeat(64), false)));
    assertEquals("UNRESOLVED", compared.status());
    assertTrue(compared.unresolved());
    assertFalse(compared.truthVerified());
  }

  /** 補助エージェントへの委譲で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void supportedAgreementReportsOnlyAgreementAndKeepsIndependentRunIds() {
    var compared =
        service.compare(
            owner,
            List.of(answer("same", "a".repeat(64), true), answer("same", "b".repeat(64), true)));
    assertEquals("AGREEMENT_REPORTED", compared.status());
    assertFalse(compared.truthVerified());
    assertEquals(
        2,
        compared.sources().stream().map(SubAgentConsensusService.Source::runId).distinct().count());
  }

  /** 補助エージェントへの委譲で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void ownerHashAndDuplicateGuardsFailBeforeAnyJudge() {
    var first = answer("yes", "a".repeat(64), true);
    var second = answer("no", "b".repeat(64), true);
    assertThrows(
        IllegalArgumentException.class, () -> service.compare(owner, List.of(first, first)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.compare(
                owner,
                List.of(
                    first,
                    new SubAgentConsensusService.Reference(second.childId(), "f".repeat(64)))));
    var other =
        new AgentRunContext("other", "other-session", owner.projectRoot(), owner.projectId());
    assertThrows(
        IllegalArgumentException.class, () -> service.compare(other, List.of(first, second)));
    verifyNoInteractionsExceptBaseline();
  }

  /** 補助エージェントへの委譲で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void boundsDisabledSettingsAndDifferentTasksRejectBeforeJudging() {
    var first = answer("yes", "a".repeat(64), true);
    var second = answer("yes", "b".repeat(64), true);
    assertThrows(IllegalArgumentException.class, () -> service.compare(owner, List.of(first)));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.compare(owner, java.util.Collections.nCopies(9, first)));
    properties.setConsensusEnabled(false);
    assertThrows(
        IllegalArgumentException.class, () -> service.compare(owner, List.of(first, second)));
    properties.setConsensusEnabled(true);
    var different =
        repository.create(owner, "reviewer", "a different task", null, 2, 0, "baseline");
    repository.claim(owner, different.id(), 0, "other-run");
    repository.complete(
        different.id(), "other-run", "COMPLETED", repository.get(owner, second.childId()).result());
    var ref =
        new SubAgentConsensusService.Reference(
            different.id(), repository.get(owner, different.id()).resultHash());
    assertThrows(IllegalArgumentException.class, () -> service.compare(owner, List.of(first, ref)));
    verifyNoInteractionsExceptBaseline();
  }

  /** 任意評価の判断について、共有予算を利用することを確認する。 */
  @Test
  void optionalJudgeUsesSharedBudgetAndCannotReplaceUnresolvedEvidence() {
    var references =
        List.of(answer("yes", "a".repeat(64), true), answer("no", "b".repeat(64), true));
    var events = new AgentEventFactory(Clock.systemUTC());
    var run =
        new RunExecutionContext(
            "parent", new OutputLimitRunBudget(0, 1), null, events, event -> {});
    run.setRunContext(owner);
    properties.setConsensusJudgeEnabled(true);
    when(runner.run(eq("judge"), anyString(), anyString(), any()))
        .thenAnswer(
            call -> {
              assertTrue(call.getArgument(2, String.class).contains("untrusted"));
              var reservation = call.getArgument(3, OutputLimitRunBudget.LlmCallReservation.class);
              assertTrue(reservation.tryReserve());
              assertEquals(0, run.sharedLlmReservation().remaining());
              return new SubAgentResult(
                  "judge",
                  "judge-run",
                  SubAgentResult.Status.COMPLETED,
                  "judge prefers yes",
                  Instant.now(),
                  Instant.now());
            });
    var judged = service.judge(run, references, "judge");
    assertTrue(judged.comparison().unresolved());
    assertEquals("DISAGREEMENT", judged.comparison().status());
    assertEquals(0, run.sharedLlmReservation().remaining());
    assertEquals("judge-run", judged.judge().subAgentRunId());
    assertThrows(IllegalArgumentException.class, () -> service.judge(run, references, "reviewer"));
  }

  /** 補助エージェントへの委譲で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void definitionChangeAndMissingContractCannotUpgradeClaimedEvidence() {
    var refs = List.of(answer("same", "a".repeat(64), true), answer("same", "b".repeat(64), true));
    when(runner.durableBaseline(any(), eq(owner))).thenReturn("changed");
    var compared = service.compare(owner, refs);
    assertEquals("UNRESOLVED", compared.status());
    assertTrue(compared.unresolved());
    assertTrue(
        compared.sources().stream().noneMatch(SubAgentConsensusService.Source::evidenceValidated));
  }
}
