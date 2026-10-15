package dev.mikoto2000.rei.chatgpt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.policy.ToolPermissionGuard;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PlanAppServerClientTest {
  @TempDir Path root;
  final List<PlanClient.Input> input =
      List.of(
          new PlanClient.Input(
              "user", "{\"sources\":[{\"path\":\"docs/source.md\",\"content\":\"approved\"}]}"));

  TokenSet tokens() {
    return new TokenSet(
        "own",
        "subject",
        "email",
        "id",
        "access-secret",
        "refresh-secret",
        Set.of(),
        Instant.now().plusSeconds(100).getEpochSecond());
  }

  @Test
  void onlyOwnLeaseIsPassedAndReadUsesActualRun() {
    var accounts = mock(PlanClient.class);
    var guard = mock(ToolPermissionGuard.class);
    when(accounts.lease("account", "model")).thenReturn(new PlanClient.Lease(tokens(), () -> true));
    var session =
        new PlanAppServerRpcTest.Fake(
            "{\"id\":91,\"method\":\"item/tool/call\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"tool\":\"rei_read_source\",\"callId\":\"call-1\",\"arguments\":{\"path\":\"docs/source.md\"}}}",
            PlanAppServerRpcTest.USAGE,
            PlanAppServerRpcTest.done("completed"));
    var client =
        new PlanAppServerClient(
            accounts,
            (own, t, c) -> {
              assertEquals("access-secret", own.accessToken());
              return new PlanAppServerClient.Started(session, "/isolated/workspace");
            },
            guard);
    var owner = new AgentRunContext("run", "session", root, "project");
    try (var scope = AgentRunScope.open(owner)) {
      assertEquals(
          PlanSseAssembler.State.COMPLETED,
          client
              .inferHistory(
                  "account",
                  "model",
                  "instructions",
                  input,
                  Duration.ofSeconds(1),
                  4096,
                  4096,
                  () -> false,
                  () -> {})
              .state());
    }
    verify(guard).check("readFile", "docs/source.md", owner);
    assertEquals(1, client.takeTrace().sourceReads());
    assertNull(client.takeTrace());
    assertTrue(session.closed);
  }

  @Test
  void logoutDuringStartupPreventsTurnAndClosesProcess() {
    var valid = new AtomicBoolean(true);
    var accounts = mock(PlanClient.class);
    when(accounts.lease(anyString(), anyString()))
        .thenReturn(new PlanClient.Lease(tokens(), valid::get));
    var s = new PlanAppServerRpcTest.Fake();
    var client =
        new PlanAppServerClient(
            accounts,
            (own, t, c) -> {
              valid.set(false);
              return new PlanAppServerClient.Started(s, "/isolated/workspace");
            },
            mock(ToolPermissionGuard.class));
    try (var scope = AgentRunScope.open(new AgentRunContext("run", "session", root, "project"))) {
      assertEquals(
          ChatGptException.Code.CANCELLED,
          assertThrows(
                  ChatGptException.class,
                  () ->
                      client.inferHistory(
                          "a",
                          "model",
                          "instructions",
                          input,
                          Duration.ofSeconds(1),
                          4096,
                          4096,
                          () -> false,
                          () -> {}))
              .code());
    }
    assertTrue(s.closed);
    assertTrue(s.sent.isEmpty());
  }

  @Test
  void missingRunAndOversizedInputNeverAcquireCredentialsOrStartProcess() {
    var accounts = mock(PlanClient.class);
    var calls = new AtomicBoolean();
    var client =
        new PlanAppServerClient(
            accounts,
            (o, t, c) -> {
              calls.set(true);
              throw new AssertionError();
            },
            mock(ToolPermissionGuard.class));
    assertThrows(
        ChatGptException.class,
        () ->
            client.inferHistory(
                "a", "m", "i", input, Duration.ofSeconds(1), 10, 4096, () -> false, () -> {}));
    try (var scope = AgentRunScope.open(new AgentRunContext("run", "session", root, "project"))) {
      assertEquals(
          ChatGptException.Code.SEND_LIMIT,
          assertThrows(
                  ChatGptException.class,
                  () ->
                      client.inferHistory(
                          "a",
                          "m",
                          "i",
                          input,
                          Duration.ofSeconds(1),
                          10,
                          4096,
                          () -> false,
                          () -> {}))
              .code());
    }
    verifyNoInteractions(accounts);
    assertFalse(calls.get());
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
  void wslEnvironmentDoesNotCarryAmbientKeysOrTokenArguments() {
    var settings = new PlanAppServerProperties(true, root, "Ubuntu-24.04", "/opt/codex/codex");
    var launcher = new WslPlanAppServerLauncher(settings);
    var process =
        launcher.process(
            root,
            "/isolated/workspace",
            "access-secret",
            List.of("app-server", "--listen", "stdio://"),
            "1805s");
    assertFalse(process.command().toString().contains("access-secret"));
    assertEquals("access-secret", process.environment().get("REI_PLAN_ACCESS_TOKEN"));
    assertTrue(process.command().contains("1805s"));
    assertFalse(process.command().contains("125s"));
    assertFalse(process.environment().containsKey("OPENAI_API_KEY"));
    assertFalse(process.environment().containsKey("USERPROFILE"));
    assertTrue(process.environment().get("WSLENV").contains("REI_PLAN_ACCESS_TOKEN/u"));
    assertEquals(root.toString(), process.environment().get("CODEX_HOME"));
    assertTrue(WslPlanAppServerLauncher.configuration().contains("inherit = \"none\""));
    assertTrue(WslPlanAppServerLauncher.configuration().contains("shell_tool = false"));
  }

  @Test
  void nativeToolContinuationReservesAndRecordsCumulativeUsageAsDeltas() {
    var accounts = mock(PlanClient.class);
    when(accounts.lease(anyString(), anyString()))
        .thenReturn(new PlanClient.Lease(tokens(), () -> true));
    var s =
        new PlanAppServerRpcTest.Fake(
            PlanAppServerRpcTest.USAGE,
            "{\"id\":91,\"method\":\"item/tool/call\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"tool\":\"rei_read_source\",\"arguments\":{\"path\":\"docs/source.md\"}}}",
            PlanAppServerRpcTest.USAGE.replace("17", "34"),
            PlanAppServerRpcTest.done("completed"));
    var client =
        new PlanAppServerClient(
            accounts,
            (o, t, c) -> new PlanAppServerClient.Started(s, "/isolated/workspace"),
            mock(ToolPermissionGuard.class));
    var budget = mock(dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation.class);
    when(budget.tryReserve()).thenReturn(true);
    client.nativeBudget(budget, 2);
    try (var scope = AgentRunScope.open(new AgentRunContext("run", "session", root, "project"))) {
      assertEquals(
          34,
          client
              .inferHistory(
                  "a",
                  "model",
                  "instructions",
                  input,
                  Duration.ofSeconds(1),
                  4096,
                  4096,
                  () -> false,
                  () -> {})
              .totalTokens());
    }
    verify(budget).tryReserve();
    verify(budget, times(2)).recordTotalTokens(17);
    assertEquals(34, client.takeNativeTokens());
    assertEquals(0, client.takeNativeTokens());
  }

  @Test
  void exhaustedNativeContinuationInterruptsBeforeToolReply() {
    var accounts = mock(PlanClient.class);
    when(accounts.lease(anyString(), anyString()))
        .thenReturn(new PlanClient.Lease(tokens(), () -> true));
    var s =
        new PlanAppServerRpcTest.Fake(
            PlanAppServerRpcTest.USAGE,
            "{\"id\":91,\"method\":\"item/tool/call\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"tool\":\"rei_read_source\",\"arguments\":{\"path\":\"docs/source.md\"}}}",
            PlanAppServerRpcTest.done("interrupted"));
    var client =
        new PlanAppServerClient(
            accounts,
            (o, t, c) -> new PlanAppServerClient.Started(s, "/isolated/workspace"),
            mock(ToolPermissionGuard.class));
    var budget = mock(dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation.class);
    when(budget.tryReserve()).thenReturn(false);
    client.nativeBudget(budget, 2);
    try (var scope = AgentRunScope.open(new AgentRunContext("run", "session", root, "project"))) {
      assertEquals(
          ChatGptException.Code.PERMISSION_DENIED,
          client
              .inferHistory(
                  "a",
                  "model",
                  "instructions",
                  input,
                  Duration.ofSeconds(1),
                  4096,
                  4096,
                  () -> false,
                  () -> {})
              .error());
    }
    int interruptIndex = -1, replyIndex = -1;
    for (int i = 0; i < s.sent.size(); i++) {
      if (s.sent.get(i).path("method").asText().equals("turn/interrupt")) interruptIndex = i;
      if (s.sent.get(i).path("id").asInt() == 91) replyIndex = i;
    }
    assertTrue(interruptIndex >= 0 && replyIndex > interruptIndex);
    assertTrue(s.closed);
    verify(budget).recordTotalTokens(17);
    assertEquals(17, client.takeNativeTokens());
  }

  @Test
  void maskedQuotedReferencesCanReturnThroughTheNativeAdapterWhileUnmaskedRepliesStayRestricted()
      throws Exception {
    for (boolean masked : List.of(true, false)) {
      var accounts = mock(PlanClient.class);
      when(accounts.lease(anyString(), anyString()))
          .thenReturn(new PlanClient.Lease(tokens(), () -> true));
      String reply =
          PlanAppServerRpcTest.JSON.writeValueAsString(
              Map.of(
                  "references",
                  List.of(
                      Map.of(
                          "quote",
                          "spring.datasource.password=\""
                              + (masked ? "[REDACTED]" : "unmasked-reply-value")
                              + "\""))));
      var terminal = PlanAppServerRpcTest.JSON.readTree(PlanAppServerRpcTest.done("completed"));
      ((com.fasterxml.jackson.databind.node.ObjectNode)
              terminal.path("params").path("turn").path("items").get(0))
          .put("text", reply);
      var session = new PlanAppServerRpcTest.Fake(PlanAppServerRpcTest.USAGE, terminal.toString());
      var client =
          new PlanAppServerClient(
              accounts,
              (own, t, c) -> new PlanAppServerClient.Started(session, "/isolated/workspace"),
              mock(ToolPermissionGuard.class));
      try (var scope = AgentRunScope.open(new AgentRunContext("run", "session", root, "project"))) {
        var result =
            client.inferHistory(
                "account",
                "model",
                "instructions",
                input,
                Duration.ofSeconds(1),
                4096,
                4096,
                () -> false,
                () -> {});
        assertEquals(
            masked ? PlanSseAssembler.State.COMPLETED : PlanSseAssembler.State.FAILED,
            result.state());
        assertEquals(masked ? reply : "", result.text());
        if (!masked) assertEquals(ChatGptException.Code.PERMISSION_DENIED, result.error());
      }
    }
  }
}
