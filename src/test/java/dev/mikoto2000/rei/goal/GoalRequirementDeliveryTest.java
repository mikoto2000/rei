package dev.mikoto2000.rei.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.mikoto2000.rei.artifact.Artifact;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 目標要件配信の結果と状態の契約を検証するテスト。 */
class GoalRequirementDeliveryTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** 検証に使う作業ルート。 */
  Path root;

  /** 検証に使う目標一覧。 */
  GoalRepository goals;

  /** 検証に使う確認の窓口。 */
  FileGoalVerifier verifier;

  /** 検証に使う実行可否の判断。 */
  GoalCompletionGate gate;

  /** 検証に使う成果物。 */
  Artifact artifact;

  /** {@code owner} の条件を再現するための補助情報を返す。 */
  AgentRunContext owner() {
    return new AgentRunContext("human", "session", root, "project");
  }

  /** {@code sha} の条件を再現するための補助情報を返す。 */
  String sha(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() throws Exception {
    root = directory.toRealPath();
    goals =
        new GoalRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + root.resolve("goals.db")),
            Clock.systemUTC());
    gate =
        new GoalCompletionGate(
            goals,
            (o, r) -> {
              throw new java.io.IOException("no review");
            },
            (o, r) -> artifact,
            (r, d) -> {
              throw new java.io.IOException("no patch");
            },
            Clock.systemUTC(),
            false);
    verifier = new FileGoalVerifier();
    verifier.setCompletionGate(gate);
    Files.writeString(root.resolve("out.txt"), "correct");
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  GoalRepository.Goal create(GoalCompletionGate.Definition definition) throws Exception {
    return goals.create(
        owner(),
        "requirements",
        List.of(new GoalRepository.FileCriterion("out.txt", sha("correct"))),
        definition,
        3,
        3);
  }

  /** 目標の達成管理で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void namedMandatoryConditionsBlockButOptionalConditionsDoNotAndRestoreAfterRestart()
      throws Exception {
    var definition =
        new GoalCompletionGate.Definition(
            List.of(new GoalRepository.FileCriterion("out.txt", sha("correct"))),
            null,
            List.of(),
            List.of(),
            null,
            List.of(
                new GoalCompletionGate.Requirement(
                    "R1",
                    "mandatory check",
                    true,
                    new GoalRepository.FileCriterion("state.json", null, "/done", "true")),
                new GoalCompletionGate.Requirement(
                    "R2",
                    "optional file",
                    false,
                    new GoalRepository.FileCriterion("optional.txt", sha("optional")))));
    var goal = create(definition);
    assertEquals("completion_requirement_unmet", verifier.verify(goal).reason());
    Files.writeString(root.resolve("state.json"), "{\"done\":true}");
    assertTrue(verifier.verify(goals.get("project", goal.id())).satisfied());
    var restarted =
        new GoalRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + root.resolve("goals.db")),
            Clock.systemUTC());
    assertEquals(definition, restarted.get("project", goal.id()).completion());
    assertTrue(verifier.verify(restarted.get("project", goal.id())).satisfied());
  }

  /** 目標の達成管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void savedArtifactDoesNotProveHumanHandoverAndModelCannotAttachIt() throws Exception {
    artifact =
        new Artifact(
            UUID.randomUUID().toString(),
            "RUN",
            "project",
            "session",
            "source",
            null,
            "text/plain",
            "result.txt",
            7,
            sha("correct"),
            Instant.now(),
            Instant.now().plusSeconds(600),
            "opaque",
            "AVAILABLE");
    var definition =
        new GoalCompletionGate.Definition(
            List.of(new GoalRepository.FileCriterion("out.txt", sha("correct"))),
            null,
            List.of(
                new GoalCompletionGate.ArtifactRequirement(
                    "result.txt", "text/plain", sha("correct"), true)),
            List.of(),
            null);
    var goal = create(definition);
    var reference = new GoalCompletionGate.Reference(artifact.artifactId(), artifact.sha256());
    gate.attach(owner(), goal.id(), new GoalCompletionGate.Proof(null, List.of(reference)), true);
    assertEquals(
        "completion_delivery_pending", verifier.verify(goals.get("project", goal.id())).reason());
    var claim = goals.claim("project", goal.id());
    String run = goals.beginAttempt(claim);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            gate.attach(
                new AgentRunContext(run, "session", root, "project"),
                goal.id(),
                new GoalCompletionGate.Proof(null, List.of(reference), List.of(reference)),
                false));
    goals.stop(claim, "BLOCKED", "completion_delivery_pending");
    gate.attach(
        owner(),
        goal.id(),
        new GoalCompletionGate.Proof(null, List.of(reference), List.of(reference)),
        true);
    assertTrue(verifier.verify(goals.get("project", goal.id())).satisfied());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            GoalRepository.parseCompletionProof(
                "{\"artifacts\":[],\"deliveredArtifacts\":[{\"id\":\""
                    + reference.id()
                    + "\",\"sha256\":\""
                    + reference.sha256()
                    + "\"}]}"));
  }

  /** 目標の達成管理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void invalidRequirementCannotWeakenVerification() throws Exception {
    var base =
        new GoalCompletionGate.Definition(
            List.of(new GoalRepository.FileCriterion("out.txt", sha("correct"))),
            null,
            List.of(),
            List.of(),
            null,
            List.of(
                new GoalCompletionGate.Requirement(
                    "R1", "exists only", true, new GoalRepository.FileCriterion("out.txt", null))));
    assertThrows(IllegalArgumentException.class, () -> create(base));
  }

  /** 目標の達成管理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void runningVerificationAndRepairAreDistinguishedWithoutChangingClaimsOrBudgets()
      throws Exception {
    var goal = goals.create(owner(), "repair", "state.json", sha("correct"), 3, 3);
    var seen = new ArrayList<String>();
    var attempts = new java.util.concurrent.atomic.AtomicInteger();
    var loop =
        new GoalLoopService(
            goals,
            verifier,
            (claim, run, done) -> {
              seen.add(goals.completionPhase("project", goal.id()));
              assertTrue(goals.reserveLlm(claim));
              try {
                Files.writeString(
                    root.resolve("state.json"),
                    attempts.incrementAndGet() == 1 ? "wrong" : "correct");
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
              done.accept(
                  new GoalLoopService.Outcome(
                      dev.mikoto2000.rei.core.chat.ChatExecutionResult.success("done", false)));
            },
            new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true, null, null, null),
            new GoalEvents(e -> {}, Clock.systemUTC()));
    assertEquals("COMPLETED", loop.run("project", goal.id()).status());
    assertEquals(List.of("RUNNING", "REPAIRING"), seen);
    assertEquals("COMPLETED", goals.completionPhase("project", goal.id()));
    assertEquals(2, goals.get("project", goal.id()).llmCallsUsed());
    var restored =
        new GoalRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + root.resolve("goals.db")),
            Clock.systemUTC());
    assertEquals("COMPLETED", restored.completionPhase("project", goal.id()));
  }

  /** 目標の達成管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void sameFilenameCannotSubstituteDifferentRevisionForHandover() throws Exception {
    var correct =
        new Artifact(
            UUID.randomUUID().toString(),
            "RUN",
            "project",
            "session",
            "source",
            null,
            "text/plain",
            "result.txt",
            7,
            sha("correct"),
            Instant.now(),
            Instant.now().plusSeconds(600),
            "opaque",
            "AVAILABLE");
    var wrong =
        new Artifact(
            UUID.randomUUID().toString(),
            "RUN",
            "project",
            "session",
            "source",
            null,
            "text/plain",
            "result.txt",
            5,
            sha("wrong"),
            Instant.now(),
            Instant.now().plusSeconds(600),
            "opaque2",
            "AVAILABLE");
    var artifacts = Map.of(correct.artifactId(), correct, wrong.artifactId(), wrong);
    gate =
        new GoalCompletionGate(
            goals,
            (o, r) -> {
              throw new java.io.IOException();
            },
            (o, r) -> artifacts.get(r.id()),
            (r, d) -> {
              throw new java.io.IOException();
            },
            Clock.systemUTC(),
            false);
    verifier.setCompletionGate(gate);
    var definition =
        new GoalCompletionGate.Definition(
            List.of(new GoalRepository.FileCriterion("out.txt", sha("correct"))),
            null,
            List.of(
                new GoalCompletionGate.ArtifactRequirement(
                    "result.txt", "text/plain", sha("correct"), true)),
            List.of(),
            null);
    var goal = create(definition);
    var a = new GoalCompletionGate.Reference(correct.artifactId(), correct.sha256());
    var b = new GoalCompletionGate.Reference(wrong.artifactId(), wrong.sha256());
    gate.attach(
        owner(), goal.id(), new GoalCompletionGate.Proof(null, List.of(a, b), List.of(b)), true);
    assertEquals(
        "completion_delivery_pending", verifier.verify(goals.get("project", goal.id())).reason());
  }

  /** 目標の達成管理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void progressShowsOptionalFailureAndCurrentRevisionWithoutClaimingCompletion() throws Exception {
    var definition =
        new GoalCompletionGate.Definition(
            List.of(new GoalRepository.FileCriterion("out.txt", sha("correct"))),
            null,
            List.of(),
            List.of(),
            null,
            List.of(
                new GoalCompletionGate.Requirement(
                    "optional",
                    "optional",
                    false,
                    new GoalRepository.FileCriterion("optional.txt", sha("optional")))));
    var goal = create(definition);
    var loop =
        new GoalLoopService(
            goals,
            verifier,
            (c, r, d) -> fail("read only"),
            new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(false, null, null, null),
            new GoalEvents(e -> {}, Clock.systemUTC()));
    var view = loop.progress("project", goal.id());
    assertEquals("READY", view.state());
    assertTrue(view.verification().satisfied());
    assertEquals(sha("correct"), view.conditions().getFirst().revision());
    assertFalse(view.conditions().getLast().required());
    assertFalse(view.conditions().getLast().satisfied());
    assertEquals("READY", goals.get("project", goal.id()).status());
    assertEquals(0, goals.get("project", goal.id()).llmCallsUsed());
  }

  /** 目標の達成管理で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void verificationAndRepairPhaseArePublishedThroughExistingGoalEvents() throws Exception {
    var goal = goals.create(owner(), "repair", "state.json", sha("correct"), 3, 3);
    var phases = new ArrayList<String>();
    var counter = new java.util.concurrent.atomic.AtomicInteger();
    var events =
        new GoalEvents(
            e -> {
              if (e.payload() instanceof dev.mikoto2000.rei.event.GoalLifecyclePayload p)
                phases.add(p.completionPhase());
            },
            Clock.systemUTC());
    var loop =
        new GoalLoopService(
            goals,
            verifier,
            (c, r, d) -> {
              assertTrue(goals.reserveLlm(c));
              try {
                Files.writeString(
                    root.resolve("state.json"),
                    counter.incrementAndGet() == 1 ? "wrong" : "correct");
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
              d.accept(
                  new GoalLoopService.Outcome(
                      dev.mikoto2000.rei.core.chat.ChatExecutionResult.success("done", false)));
            },
            new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true, null, null, null),
            events);
    assertEquals("COMPLETED", loop.run("project", goal.id()).status());
    assertEquals(List.of("RUNNING", "VERIFYING", "REPAIRING", "VERIFYING", "COMPLETED"), phases);
  }

  /** 目標の達成管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void unknownRequirementEvidenceIsBlockedInsteadOfTreatedAsRepairableMismatch() throws Exception {
    Files.writeString(root.resolve("state.json"), "malformed");
    var definition =
        new GoalCompletionGate.Definition(
            List.of(new GoalRepository.FileCriterion("out.txt", sha("correct"))),
            null,
            List.of(),
            List.of(),
            null,
            List.of(
                new GoalCompletionGate.Requirement(
                    "R1",
                    "required",
                    true,
                    new GoalRepository.FileCriterion("state.json", null, "/done", "true"))));
    var goal = create(definition);
    assertEquals("json_file_invalid", verifier.verify(goal).reason());
  }

  /** 目標の達成管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void omittedRequiredFlagMustNotSilentlyTurnAHumanConditionOptional() throws Exception {
    var definition =
        new GoalCompletionGate.Definition(
            List.of(new GoalRepository.FileCriterion("out.txt", sha("correct"))),
            null,
            List.of(),
            List.of(),
            null,
            List.of(
                new GoalCompletionGate.Requirement(
                    "R1",
                    "mandatory",
                    true,
                    new GoalRepository.FileCriterion("state.json", null, "/done", "true"))));
    String json =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .writeValueAsString(definition)
            .replace("\"required\":true,", "");
    assertThrows(IllegalArgumentException.class, () -> GoalRepository.parseCompletion(json));
  }
}
