package dev.mikoto2000.rei.slack;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ChatExecutionResult;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Slackエージェントサービスの結果と状態の契約を検証するテスト。 */
@Tag("integration")
class SlackAgentServiceTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  final Clock clock = Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"), ZoneOffset.UTC);

  /** {@code owner} の条件を再現するための補助情報を返す。 */
  AgentRunContext owner() {
    return new AgentRunContext("run", "session", dir, "project");
  }

  /** 検証で使用する設定を、指定された条件に合わせて用意する。 */
  SlackAgentProperties settings() {
    var p = new SlackAgentProperties();
    p.setEnabled(true);
    p.setAllowedWorkspaceIds(Set.of("T1"));
    p.setAllowedChannelIds(Set.of("C1"));
    p.setAgents(
        Map.of("tenten", new SlackAgentProperties.Agent("T1", "U2", "B2", "C1", "U2", "FINAL:")));
    return p;
  }

  /**
   * 検証条件の再現に使う検証条件の情報。
   *
   * @param repo 検証に使うリポジトリ
   * @param saved 検証に使う保存済み
   * @param registry 検証に使う登録台帳
   * @param service 検証に使うサービス
   * @param source 検証に使う取得元
   */
  record Fixture(
      SlackAgentRepository repo,
      SlackAgentContinuationStore saved,
      RunRegistry registry,
      SlackAgentService service,
      DriverManagerDataSource source) {}

  /** {@code fixture} の条件を再現するための補助情報を返す。 */
  Fixture fixture() {
    var source = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("storage.db"));
    var repo = new SlackAgentRepository(source, clock);
    var saved = new SlackAgentContinuationStore(source);
    var registry = new RunRegistry(clock, source);
    registry.register(owner());
    registry.transition("run", RunStatus.RUNNING, null);
    var bus = new InMemoryAgentEventBus();
    var router = new ConversationInputRouter(Runnable::run, (o, p, q) -> {});
    var runs =
        new RunService(
            registry,
            bus,
            new AgentEventFactory(clock),
            new dev.mikoto2000.rei.core.service.CommandCancellationService(),
            router::cancelQueued);
    var service = new SlackAgentService(settings(), repo, saved, registry, runs, router, clock);
    return new Fixture(repo, saved, registry, service, source);
  }

  /** {@code pause} の条件を再現するため、検証用の操作や状態を扱う。 */
  void pause(Fixture f) {
    var e =
        new RunExecutionContext(
            "run",
            new OutputLimitRunBudget(1, 3),
            new ProgressEvaluator(dir, null),
            new AgentEventFactory(clock),
            event -> {});
    e.setRunContext(owner());
    e.setUserRequest("original request");
    var history =
        List.<Message>of(
            new UserMessage("original request"),
            AssistantMessage.builder()
                .content("")
                .toolCalls(
                    List.of(
                        new AssistantMessage.ToolCall(
                            "call", "function", "askExternalAgent", "{}")))
                .build(),
            ToolResponseMessage.builder()
                .responses(
                    List.of(
                        new ToolResponseMessage.ToolResponse(
                            "call", "askExternalAgent", "WAITING")))
                .build());
    assertTrue(f.service().suspend(e, history));
    assertEquals(RunStatus.WAITING, f.registry().get("run").status());
  }

  /** Slackとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void replyResumesSameParentOnceWithUntrustedToolResult() {
    var f = fixture();
    var q = f.repo().create(owner(), "call", "tenten", "question", "", 300, null, settings());
    pause(f);
    var sends = new AtomicInteger();
    var resumes = new AtomicInteger();
    f.service()
        .attach(
            "U1",
            "T1",
            row -> {
              sends.incrementAndGet();
              assertNull(row.thread());
              assertTrue(row.text().startsWith("<@U2>"));
              return SlackTransport.Delivery.sent("100.1");
            });
    f.service()
        .onResume(
            (o, saved) -> {
              assertEquals(owner(), o);
              assertInstanceOf(ToolResponseMessage.class, saved.messages().getLast());
              assertTrue(
                  saved.messages().getLast().getText() == null
                      || !saved.messages().getLast().getText().contains("FINAL:"));
              var data =
                  ((ToolResponseMessage) saved.messages().getLast())
                      .getResponses()
                      .getFirst()
                      .responseData();
              assertTrue(data.contains("UNTRUSTED DATA"));
              assertTrue(data.contains("result"));
              resumes.incrementAndGet();
              return ChatExecutionResult.success("integrated", false);
            });
    f.service().pump();
    assertEquals("WAITING", f.repo().get(q.id()).state());
    assertEquals(0, resumes.get());
    assertTrue(
        f.service()
            .receive(
                new SlackMessage(
                    "E1",
                    "T1",
                    "C1",
                    "101.1",
                    "100.1",
                    "U2",
                    "B2",
                    "bot_message",
                    "message",
                    "channel",
                    "FINAL: result")));
    f.service().pump();
    f.service().pump();
    assertEquals(1, resumes.get());
    assertEquals(1, sends.get());
    assertEquals(Set.of("run"), f.registry().runIds());
    assertEquals(RunStatus.COMPLETED, f.registry().get("run").status());
  }

  /** Slackとの連携の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancelAfterSendingNeverResumesOnLateReply() {
    var f = fixture();
    f.repo().create(owner(), "call", "tenten", "question", "", 300, null, settings());
    pause(f);
    f.service().attach("U1", "T1", row -> SlackTransport.Delivery.sent("100.1"));
    f.service().pump();
    f.service().cancel("run");
    assertFalse(
        f.service()
            .receive(
                new SlackMessage(
                    "late",
                    "T1",
                    "C1",
                    "101.1",
                    "100.1",
                    "U2",
                    "B2",
                    "bot_message",
                    "message",
                    "channel",
                    "FINAL: late")));
    f.service()
        .onResume(
            (o, s) -> {
              fail("Cancelled Run resumed");
              return null;
            });
    f.service().pump();
    assertEquals("CANCELLED", f.saved().state("run"));
  }

  /** 不明について、報告された内容を送信することを確認する。 */
  @Test
  void unknownSendIsReportedWithoutReposting() {
    var f = fixture();
    var q = f.repo().create(owner(), "call", "tenten", "question", "", 300, null, settings());
    pause(f);
    var sends = new AtomicInteger();
    f.service()
        .attach(
            "U1",
            "T1",
            row -> {
              sends.incrementAndGet();
              return SlackTransport.Delivery.unknown();
            });
    f.service()
        .onResume(
            (o, s) -> {
              assertTrue(
                  ((ToolResponseMessage) s.messages().getLast())
                      .getResponses()
                      .getFirst()
                      .responseData()
                      .contains("UNKNOWN"));
              return ChatExecutionResult.success("uncertain", false);
            });
    f.service().pump();
    f.service().pump();
    assertEquals(1, sends.get());
    assertEquals("UNKNOWN", f.repo().get(q.id()).state());
  }

  /** 返信変更前について、応答永続化されたを送信することを確認する。 */
  @Test
  void replyBeforeSendResponseIsDurableAndMatchedAfterTimestampIsKnown() {
    var f = fixture();
    var q = f.repo().create(owner(), "call", "tenten", "question", "", 300, null, settings());
    pause(f);
    var accepted = new java.util.concurrent.atomic.AtomicBoolean();
    var resumes = new AtomicInteger();
    f.service()
        .attach(
            "U1",
            "T1",
            row -> {
              accepted.set(
                  f.service()
                      .receive(
                          new SlackMessage(
                              "early",
                              "T1",
                              "C1",
                              "101.1",
                              "100.1",
                              "U2",
                              "B2",
                              "bot_message",
                              "message",
                              "channel",
                              "FINAL: early result")));
              return SlackTransport.Delivery.sent("100.1");
            });
    f.service()
        .onResume(
            (o, s) -> {
              resumes.incrementAndGet();
              return ChatExecutionResult.success("done", false);
            });
    f.service().pump();
    assertTrue(accepted.get());
    assertEquals("COMPLETED", f.repo().get(q.id()).state());
    assertEquals(1, resumes.get());
    assertEquals(1, f.repo().responses(q.id()).size());
  }

  /** Slackとの連携で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void rateLimitBackoffAppliesToEntireChannelAfterRestart() {
    var f = fixture();
    var q = f.repo().create(owner(), "call", "tenten", "question", "", 300, null, settings());
    f.repo().create(owner(), "second", "tenten", "second question", "", 300, null, settings());
    pause(f);
    f.repo().claimSend(q.id());
    f.repo().delivery(q.id(), SlackTransport.Delivery.retry(7200));
    f.service()
        .attach(
            "U1",
            "T1",
            row -> {
              fail("Sibling request bypassed durable channel Retry-After");
              return null;
            });
    f.service().pump();
  }

  /** Slackとの連携の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationDuringSendKeepsCancelledStateAndKnownTimestamp() {
    var f = fixture();
    var q = f.repo().create(owner(), "call", "tenten", "question", "", 300, null, settings());
    pause(f);
    f.service()
        .attach(
            "U1",
            "T1",
            row -> {
              f.service().cancel("run");
              return SlackTransport.Delivery.sent("100.1");
            });
    assertDoesNotThrow(f.service()::pump);
    assertEquals("CANCELLED", f.repo().get(q.id()).state());
    assertEquals("100.1", f.repo().get(q.id()).thread());
  }

  /** Slackとの連携で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void verifiesConfiguredBotIdAgainstBotUserAndRejectsSelf() {
    var f = fixture();
    assertDoesNotThrow(() -> f.service().verifyIdentities("U1", "T1", user -> "B2"));
    assertThrows(
        IllegalArgumentException.class,
        () -> f.service().verifyIdentities("U1", "T1", user -> "B3"));
    assertThrows(
        IllegalArgumentException.class,
        () -> f.service().verifyIdentities("U2", "T1", user -> "B2"));
    assertThrows(
        IllegalArgumentException.class,
        () -> f.service().verifyIdentities("U1", "T2", user -> "B2"));
  }

  /** タイムアウトについて、部分的な結果確認記録を返すことを確認する。 */
  @Test
  void timeoutReturnsPartialAcknowledgementAndUncertaintyInsteadOfCompletion() {
    var f = fixture();
    var q = f.repo().create(owner(), "call", "tenten", "question", "", 1, null, settings());
    pause(f);
    f.service().attach("U1", "T1", row -> SlackTransport.Delivery.sent("100.1"));
    f.service().pump();
    assertTrue(
        f.service()
            .receive(
                new SlackMessage(
                    "ack",
                    "T1",
                    "C1",
                    "101.1",
                    "100.1",
                    "U2",
                    "B2",
                    "bot_message",
                    "message",
                    "channel",
                    "accepted, working")));
    f.repo().expire(clock.instant().plusSeconds(2));
    f.service()
        .onResume(
            (o, s) -> {
              String data =
                  ((ToolResponseMessage) s.messages().getLast())
                      .getResponses()
                      .getFirst()
                      .responseData();
              assertTrue(data.contains("TIMED_OUT"));
              assertTrue(data.contains("accepted, working"));
              assertTrue(data.contains("not verified completion"));
              return ChatExecutionResult.success("partial, uncertain", false);
            });
    f.service().pump();
    assertEquals("TIMED_OUT", f.repo().get(q.id()).state());
    assertEquals(RunStatus.COMPLETED, f.registry().get("run").status());
  }

  /** Slackとの連携で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void earlyReplyForDifferentThreadNeverCompletesThePendingRequest() {
    var f = fixture();
    var q = f.repo().create(owner(), "call", "tenten", "question", "", 300, null, settings());
    pause(f);
    f.service()
        .attach(
            "U1",
            "T1",
            row -> {
              assertTrue(
                  f.service()
                      .receive(
                          new SlackMessage(
                              "unrelated",
                              "T1",
                              "C1",
                              "201.1",
                              "200.1",
                              "U2",
                              "B2",
                              "bot_message",
                              "message",
                              "channel",
                              "FINAL: unrelated")));
              return SlackTransport.Delivery.sent("100.1");
            });
    f.service().pump();
    assertEquals("WAITING", f.repo().get(q.id()).state());
    assertTrue(f.repo().responses(q.id()).isEmpty());
  }

  /** Slackとの連携で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void restartResumesHumanWaitingRunOnceWithoutResendingTheSlackRequest() {
    var f = fixture();
    var q = f.repo().create(owner(), "call", "tenten", "question", "", 300, null, settings());
    pause(f);
    f.service().attach("U1", "T1", row -> SlackTransport.Delivery.sent("100.1"));
    f.service().pump();
    f.registry().close();
    var registry = new RunRegistry(clock, f.source());
    var router = new ConversationInputRouter(Runnable::run, (o, p, queue) -> {});
    var runs =
        new RunService(
            registry,
            new InMemoryAgentEventBus(),
            new AgentEventFactory(clock),
            new dev.mikoto2000.rei.core.service.CommandCancellationService(),
            router::cancelQueued);
    var service =
        new SlackAgentService(settings(), f.repo(), f.saved(), registry, runs, router, clock);
    service.recover();
    service.attach(
        "U1",
        "T1",
        row -> {
          fail("Restart reposted question");
          return null;
        });
    var resumes = new AtomicInteger();
    service.onResume(
        (o, s) -> {
          assertEquals(owner(), o);
          assertEquals("original request", s.request());
          resumes.incrementAndGet();
          return ChatExecutionResult.success("restored", false);
        });
    assertTrue(
        service.receive(
            new SlackMessage(
                "final",
                "T1",
                "C1",
                "101.1",
                "100.1",
                "U2",
                "B2",
                "bot_message",
                "message",
                "channel",
                "FINAL: result")));
    service.pump();
    service.pump();
    assertEquals(1, resumes.get());
    assertEquals(RunStatus.COMPLETED, registry.get("run").status());
    assertEquals(1, f.repo().responses(q.id()).size());
  }

  /** Slackとの連携で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void restoredManagedParentIsStoppedBeforeAnyUnsentExternalRequest() {
    var f = fixture();
    var q = f.repo().create(owner(), "call", "tenten", "question", "", 300, null, settings());
    f.service().attachParent(owner(), null, result -> {});
    pause(f);
    f.service().detachParent(owner());
    f.registry().close();
    f.service()
        .attach(
            "U1",
            "T1",
            row -> {
              fail("Orphaned managed parent sent a request");
              return null;
            });
    f.service().pump();
    assertEquals("UNKNOWN", f.repo().get(q.id()).state());
    assertEquals("UNKNOWN", f.saved().state("run"));
    assertEquals(RunStatus.UNKNOWN, f.registry().get("run").status());
  }

  /** Slackとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void relocatedProjectIsRevalidatedBeforeAnyNetworkSend() {
    var f = fixture();
    var q = f.repo().create(owner(), "call", "tenten", "question", "", 300, null, settings());
    pause(f);
    f.service()
        .onValidate(
            o -> {
              throw new IllegalStateException("moved");
            });
    f.service()
        .attach(
            "U1",
            "T1",
            row -> {
              fail("Invalid Project sent externally");
              return null;
            });
    f.service().pump();
    assertEquals("UNKNOWN", f.repo().get(q.id()).state());
    assertEquals(RunStatus.UNKNOWN, f.registry().get("run").status());
  }

  /** 利用できない通信の接続接続について、ツール受付記録を受け付けないことを確認する。 */
  @Test
  void unavailableSocketConnectionRejectsToolReceiptWithoutCreatingAnUnboundedWait() {
    var f = fixture();
    var tools =
        new SlackAgentTools(
            settings(),
            f.repo(),
            org.mockito.Mockito.mock(dev.mikoto2000.rei.core.policy.ToolPermissionGuard.class));
    tools.onRegistered(f.service()::registered);
    var e =
        new RunExecutionContext(
            "run",
            new OutputLimitRunBudget(1, 3),
            new ProgressEvaluator(dir, null),
            new AgentEventFactory(clock),
            event -> {});
    e.setRunContext(owner());
    assertThrows(
        IllegalStateException.class,
        () ->
            tools.askExternalAgent(
                "tenten",
                "question",
                "",
                1,
                null,
                new org.springframework.ai.chat.model.ToolContext(
                    Map.of(RunExecutionContext.KEY, e, "toolCallId", "call"))));
    assertTrue(f.repo().forRun("run").isEmpty());
    assertEquals(RunStatus.RUNNING, f.registry().get("run").status());
  }

  /** Slackとの連携で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void preparedConnectionRetainsOldRepliesButCannotAdmitUntilSocketOpenSucceeds() {
    var f = fixture();
    f.service().attach("U1", "T1", row -> SlackTransport.Delivery.sent("100.1"), false);
    var tools =
        new SlackAgentTools(
            settings(),
            f.repo(),
            org.mockito.Mockito.mock(dev.mikoto2000.rei.core.policy.ToolPermissionGuard.class));
    tools.onRegistered(f.service()::registered);
    var e =
        new RunExecutionContext(
            "run",
            new OutputLimitRunBudget(1, 3),
            new ProgressEvaluator(dir, null),
            new AgentEventFactory(clock),
            event -> {});
    e.setRunContext(owner());
    var context =
        new org.springframework.ai.chat.model.ToolContext(
            Map.of(RunExecutionContext.KEY, e, "toolCallId", "call"));
    assertThrows(
        IllegalStateException.class,
        () -> tools.askExternalAgent("tenten", "new question", "", 1, null, context));
    var prior =
        f.repo().create(owner(), "prior", "tenten", "prior question", "", 300, null, settings());
    f.repo().claimSend(prior.id());
    f.repo().sent(prior.id(), "100.1");
    assertTrue(
        f.service()
            .receive(
                new SlackMessage(
                    "restored",
                    "T1",
                    "C1",
                    "101.1",
                    "100.1",
                    "U2",
                    "B2",
                    "bot_message",
                    "message",
                    "channel",
                    "progress")));
    f.service().enableRequests();
    assertEquals(
        "CREATED", tools.askExternalAgent("tenten", "new question", "", 1, null, context).status());
    f.service().detach();
    assertThrows(
        IllegalStateException.class,
        () ->
            tools.askExternalAgent(
                "tenten",
                "question after detach",
                "",
                1,
                null,
                new org.springframework.ai.chat.model.ToolContext(
                    Map.of(RunExecutionContext.KEY, e, "toolCallId", "third"))));
    assertEquals(2, f.repo().forRun("run").size());
  }

  /** Slackとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void requestAndExistingDependencyCommitTogetherThenPublishTheObservedTerminalState() {
    var f = fixture();
    f.service().attach("U1", "T1", row -> SlackTransport.Delivery.sent("100.1"));
    var dependencies =
        new dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository(f.source(), clock);
    var observations =
        org.mockito.Mockito.mock(
            dev.mikoto2000.rei.core.dependency.DependencyObservationService.class);
    f.service().dependencies(dependencies, observations);
    var q =
        f.repo()
            .transaction(
                () -> {
                  var request =
                      f.repo()
                          .create(owner(), "call", "tenten", "question", "", 300, null, settings());
                  f.service().registered(request);
                  return request;
                });
    var id = f.repo().dependency(q.id());
    assertEquals("session", dependencies.get("project", id).sessionId());
    assertEquals(
        dev.mikoto2000.rei.core.dependency.DependencySpec.Kind.EXTERNAL_AGENT,
        dependencies.get("project", id).spec().kind());
    pause(f);
    f.service().attach("U1", "T1", row -> SlackTransport.Delivery.sent("100.1"));
    f.service().pump();
    f.service()
        .receive(
            new SlackMessage(
                "final",
                "T1",
                "C1",
                "101.1",
                "100.1",
                "U2",
                "B2",
                "bot_message",
                "message",
                "channel",
                "FINAL: result"));
    f.service().onResume((o, s) -> ChatExecutionResult.success("result", false));
    f.service().pump();
    assertEquals(
        dev.mikoto2000.rei.core.dependency.DependencyState.COMPLETED,
        dependencies.get("project", id).state());
    org.mockito.Mockito.verify(observations, org.mockito.Mockito.atLeastOnce()).flushFacts();
    assertThrows(
        IllegalStateException.class,
        () ->
            f.repo()
                .transaction(
                    () -> {
                      var request =
                          f.repo()
                              .create(
                                  owner(),
                                  "rollback",
                                  "tenten",
                                  "rollback",
                                  "",
                                  300,
                                  null,
                                  settings());
                      f.service().registered(request);
                      throw new IllegalStateException("rollback");
                    }));
    assertEquals(1, f.repo().forRun("run").size());
    assertEquals(1, dependencies.list("project").size());
  }

  /** Slackとの連携で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void orphanedManagedParentCannotResumeWithoutOriginalGoalBudgetAfterRestart() {
    var f = fixture();
    var q = f.repo().create(owner(), "call", "tenten", "question", "", 300, null, settings());
    f.service().attachParent(owner(), null, result -> {});
    pause(f);
    f.service().detachParent(owner());
    f.registry().close();
    assertTrue(f.repo().claimSend(q.id()));
    f.repo().sent(q.id(), "100.1");
    f.service().attach("U1", "T1", row -> SlackTransport.Delivery.unknown());
    assertTrue(
        f.service()
            .receive(
                new SlackMessage(
                    "final",
                    "T1",
                    "C1",
                    "101.1",
                    "100.1",
                    "U2",
                    "B2",
                    "bot_message",
                    "message",
                    "channel",
                    "FINAL: result")));
    f.service()
        .onResume(
            (o, s) -> {
              fail("Lost Goal reservation resumed");
              return null;
            });
    f.service().pump();
    assertEquals("UNKNOWN", f.saved().state("run"));
    assertEquals(RunStatus.UNKNOWN, f.registry().get("run").status());
    assertEquals("COMPLETED", f.repo().get(q.id()).state());
  }
}
