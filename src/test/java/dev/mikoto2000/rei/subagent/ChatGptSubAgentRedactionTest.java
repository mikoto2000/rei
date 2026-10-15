package dev.mikoto2000.rei.subagent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.chatgpt.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.policy.*;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChatGptSubAgentRedactionTest {
  static final String FIRST_SECRET = "ORIGINAL_CREDENTIAL_VALUE";
  static final String SECOND_SECRET = "CHANGED_CREDENTIAL_VALUE";
  static final String ORIGINAL = "Public example\nspring.datasource.password=" + FIRST_SECRET;
  @TempDir Path root;
  final com.fasterxml.jackson.databind.ObjectMapper json =
      new com.fasterxml.jackson.databind.ObjectMapper();
  PlanClient client;
  ToolPermissionGuard permissions;
  RunRegistry runs;
  SubAgentRegistry registry;
  List<AgentEvent> events;
  AgentRunContext owner;
  LlmCallReservation budget;
  Clock clock = Clock.systemUTC();

  @BeforeEach
  void setup() throws Exception {
    Files.writeString(root.resolve("source.md"), ORIGINAL);
    Files.writeString(
        root.resolve("review.yaml"),
        """
        id: design-review
        name: Design
        description: Review
        systemPrompt: Review supplied documents
        tools: []
        maxSteps: 5
        maxRepairs: 2
        timeout: 120s
        """);
    registry =
        new SubAgentRegistry(
            root, new SubAgentDefinitionLoader(new SubAgentToolPolicy(Set.of()), model -> true));
    assertThat(registry.reload()).isEmpty();
    client = mock(PlanClient.class);
    permissions = mock(ToolPermissionGuard.class);
    runs = new RunRegistry(clock);
    owner = new AgentRunContext("parent", "session", root, "project");
    runs.register(owner);
    runs.transition(owner.runId(), RunStatus.RUNNING, null);
    events = new ArrayList<>();
    budget = mock(LlmCallReservation.class);
    when(budget.tryReserve()).thenReturn(true);
  }

  ChatGptSubAgentBackend backend(ToolPermissionGuard guard) {
    return new ChatGptSubAgentBackend(
        client,
        new PlanProperties(true, root, Set.of("design-review")),
        registry,
        guard,
        new CommandCancellationService(),
        new AgentEventFactory(clock),
        events::add,
        clock,
        runs);
  }

  ChatGptSubAgentBackend.Request request() {
    return new ChatGptSubAgentBackend.Request(
        "design-review",
        "chatgpt-plan",
        "model",
        "default",
        "Review",
        List.of("source.md"),
        "{\"decision\":\"accept\"}");
  }

  String hash(String value) {
    return dev.mikoto2000.rei.externalagent.ImplementationProposal.sha256(
        value.getBytes(StandardCharsets.UTF_8));
  }

  com.fasterxml.jackson.databind.node.ObjectNode answer(String task) throws Exception {
    var input = json.readTree(task);
    var wrapper =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            json.readTree(ChatGptSubAgentBackendTest.VALID);
    wrapper.set("scope", input.path("scope"));
    var references = wrapper.putArray("references");
    for (var source : input.path("sources")) {
      var reference = references.addObject();
      for (String field : List.of("referenceId", "path", "sha256"))
        reference.set(field, source.get(field));
      reference.put("quote", source.path("content").asText());
    }
    var omitted = wrapper.putArray("unverified");
    if (task.contains("[REDACTED]")) omitted.add("Omitted credential values cannot be verified");
    ((com.fasterxml.jackson.databind.node.ObjectNode) wrapper.path("subagent").path("result"))
        .set("unverified", omitted.deepCopy());
    return wrapper;
  }

  void successfulInference(List<String> tasks) {
    doAnswer(
            call -> {
              ((Runnable) call.getArgument(8)).run();
              String task = call.getArgument(3);
              tasks.add(task);
              return new PlanSseAssembler.Result(
                  PlanSseAssembler.State.COMPLETED, answer(task).toString(), 17, "response", null);
            })
        .when(client)
        .infer(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            anyInt(),
            anyInt(),
            any(),
            any());
  }

  void assertNoRawCredentials(ChatGptSubAgentBackend.Receipt receipt) {
    assertThat(receipt.toString()).doesNotContain(FIRST_SECRET, SECOND_SECRET);
    assertThat(events.toString()).doesNotContain(FIRST_SECRET, SECOND_SECRET);
    assertThat(runs.get(receipt.requestId()).toString())
        .doesNotContain(FIRST_SECRET, SECOND_SECRET);
    assertThat(
            mockingDetails(client).getInvocations().stream()
                .map(invocation -> Arrays.toString(invocation.getArguments()))
                .collect(java.util.stream.Collectors.joining("\n")))
        .doesNotContain(FIRST_SECRET, SECOND_SECRET);
  }

  @Test
  void dispatchedSnapshotAndReferencesUseMaskedContentAndHashWhileOriginalFileStaysIntact()
      throws Exception {
    var tasks = new ArrayList<String>();
    successfulInference(tasks);
    var receipt = backend(permissions).run(request(), owner, false, budget);
    assertThat(receipt.state()).isEqualTo(ChatGptSubAgentBackend.State.COMPLETED);
    var task = json.readTree(tasks.getFirst());
    var source = task.path("sources").get(0);
    String masked = source.path("content").asText();
    assertThat(masked)
        .contains("[REDACTED]", "spring.datasource.password=")
        .doesNotContain(FIRST_SECRET);
    assertThat(source.path("redacted").asBoolean()).isTrue();
    assertThat(source.path("sha256").asText()).isEqualTo(hash(masked)).isNotEqualTo(hash(ORIGINAL));
    assertThat(source.path("path").asText()).isEqualTo("source.md");
    assertThat(task.path("scope").toString()).isEqualTo("[\"source.md\"]");
    var returned = json.readTree(receipt.result().output()).path("references").get(0);
    assertThat(returned.path("sha256").asText()).isEqualTo(hash(masked));
    assertThat(returned.path("quote").asText()).isEqualTo(masked);
    assertThat(Files.readString(root.resolve("source.md"))).isEqualTo(ORIGINAL);
    var instructions = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(client)
        .infer(
            anyString(),
            anyString(),
            instructions.capture(),
            anyString(),
            any(),
            anyInt(),
            anyInt(),
            any(),
            any());
    assertThat(instructions.getValue()).contains("[REDACTED]", "unverified");
    assertNoRawCredentials(receipt);
  }

  @Test
  void ordinarySourceContentKeepsItsExactBytesAndReportsNoRedaction() throws Exception {
    String content = "Public configuration explanation\nserver.port=8080\n";
    Files.writeString(root.resolve("source.md"), content);
    var tasks = new ArrayList<String>();
    successfulInference(tasks);
    var receipt = backend(permissions).run(request(), owner, false, budget);
    assertThat(receipt.state()).isEqualTo(ChatGptSubAgentBackend.State.COMPLETED);
    var source = json.readTree(tasks.getFirst()).path("sources").get(0);
    assertThat(source.path("content").asText()).isEqualTo(content);
    assertThat(source.path("redacted").asBoolean()).isFalse();
    assertThat(source.path("sha256").asText()).isEqualTo(hash(content));
    assertThat(Files.readString(root.resolve("source.md"))).isEqualTo(content);
    assertNoRawCredentials(receipt);
  }

  @ParameterizedTest
  @ValueSource(strings = {"raw-hash", "raw-quote"})
  void referencesCannotClaimOriginalHashesOrQuoteValuesAbsentFromTheMaskedSnapshot(String altered) {
    doAnswer(
            call -> {
              ((Runnable) call.getArgument(8)).run();
              String task = call.getArgument(3);
              var wrapper = answer(task);
              var reference =
                  (com.fasterxml.jackson.databind.node.ObjectNode)
                      wrapper.path("references").get(0);
              if (altered.equals("raw-hash")) reference.put("sha256", hash(ORIGINAL));
              else reference.put("quote", ORIGINAL);
              return new PlanSseAssembler.Result(
                  PlanSseAssembler.State.COMPLETED, wrapper.toString(), 17, "response", null);
            })
        .when(client)
        .infer(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            anyInt(),
            anyInt(),
            any(),
            any());
    var receipt = backend(permissions).run(request(), owner, false, budget);
    assertThat(receipt.state()).isEqualTo(ChatGptSubAgentBackend.State.FAILED);
    assertThat(receipt.error()).isEqualTo(ChatGptException.Code.VALIDATION_FAILED);
    assertThat(receipt.result().validationErrors())
        .anyMatch(error -> error.path().contains("references"));
    assertNoRawCredentials(receipt);
  }

  @Test
  void repairAndRefinementKeepEveryHistoryEntryAndQuoteWithinTheMaskedSnapshot() throws Exception {
    var history = new ArrayList<List<PlanClient.Input>>();
    doAnswer(
            call -> {
              ((Runnable) call.getArgument(8)).run();
              List<PlanClient.Input> input = call.getArgument(3);
              history.add(input);
              String output =
                  history.size() == 1
                      ? "invalid JSON"
                      : answer(input.getFirst().content()).toString();
              return new PlanSseAssembler.Result(
                  PlanSseAssembler.State.COMPLETED, output, 17, "response", null);
            })
        .when(client)
        .inferHistory(
            anyString(),
            anyString(),
            anyString(),
            anyList(),
            any(),
            anyInt(),
            anyInt(),
            any(),
            any());
    var backend = backend(permissions);
    backend.configureExecution(
        new PlanExecutionProperties(
            true, 3, 1, 1, 262144, 1048576, Duration.ofSeconds(120), false, 1));
    var receipt = backend.run(request(), owner, false, budget);
    assertThat(receipt.state()).isEqualTo(ChatGptSubAgentBackend.State.COMPLETED);
    assertThat(receipt.result().repairAttempts()).isEqualTo(1);
    assertThat(history).hasSize(3);
    for (var input : history) {
      String payload =
          input.stream()
              .map(PlanClient.Input::content)
              .collect(java.util.stream.Collectors.joining("\n"));
      assertThat(payload).contains("[REDACTED]").doesNotContain(FIRST_SECRET, SECOND_SECRET);
      var source = json.readTree(input.getFirst().content()).path("sources").get(0);
      assertThat(source.path("sha256").asText()).isEqualTo(hash(source.path("content").asText()));
    }
    assertThat(
            history.getLast().stream()
                .filter(input -> input.role().equals("assistant"))
                .map(PlanClient.Input::content)
                .collect(java.util.stream.Collectors.joining("\n")))
        .contains("[REDACTED]");
    assertThat(Files.readString(root.resolve("source.md"))).isEqualTo(ORIGINAL);
    verify(budget, times(3)).tryReserve();
    assertNoRawCredentials(receipt);
  }

  @Test
  @Tag("integration")
  void actualApprovalBindsTheMaskedContentAndRedactionMetadataBeforeAnyInference()
      throws Exception {
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + root.resolve("approvals.db"));
    var approvals = new ToolApprovalRepository(source, clock);
    var guard =
        new ToolPermissionGuard(
            new ToolPermissionPolicy(
                new ToolPermissionProperties(
                    true, Set.of(ActionCapability.READ), Set.of(), Map.of())),
            new AgentEventFactory(clock),
            events::add);
    guard.setApprovals(approvals);
    var tasks = new ArrayList<String>();
    successfulInference(tasks);
    var backend = backend(guard);
    var pending = backend.run(request(), owner, false, budget);
    assertThat(pending.error()).isEqualTo(ChatGptException.Code.CONSENT_REQUIRED);
    verifyNoInteractions(client);
    var first = approvals.list("project").getFirst();
    assertThat(first.status()).isEqualTo("PENDING");
    assertThat(first.argumentsPreview()).doesNotContain(FIRST_SECRET, SECOND_SECRET);
    approvals.decide("project", first.id(), true);
    // Different private values produce the exact same approved external snapshot.
    Files.writeString(root.resolve("source.md"), ORIGINAL.replace(FIRST_SECRET, SECOND_SECRET));
    var completed = backend.run(request(), owner, false, budget);
    assertThat(completed.state()).isEqualTo(ChatGptSubAgentBackend.State.COMPLETED);
    assertThat(approvals.get("project", first.id()).status()).isEqualTo("CONSUMED");
    var sent = json.readTree(tasks.getFirst()).path("sources").get(0);
    String masked = sent.path("content").asText();
    assertThat(sent.path("redacted").asBoolean()).isTrue();
    assertThat(sent.path("sha256").asText()).isEqualTo(hash(masked));
    // The exact approved external operation is still one-use after snapshot masking.
    var repeated = backend.run(request(), owner, false, budget);
    assertThat(repeated.error()).isEqualTo(ChatGptException.Code.CONSENT_REQUIRED);
    assertThat(approvals.list("project")).isEmpty();
    // Public changes must also invalidate the earlier exact-call approval.
    Files.writeString(
        root.resolve("source.md"),
        ORIGINAL.replace(FIRST_SECRET, SECOND_SECRET) + "\nChanged public explanation");
    var contentChanged = backend.run(request(), owner, false, budget);
    assertThat(contentChanged.error()).isEqualTo(ChatGptException.Code.CONSENT_REQUIRED);
    assertThat(approvals.list("project")).hasSize(1);
    assertThat(approvals.list("project").getFirst().id()).isNotEqualTo(first.id());
    verify(client, times(1))
        .infer(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            anyInt(),
            anyInt(),
            any(),
            any());
    assertNoRawCredentials(completed);
    assertNoRawCredentials(repeated);
    assertNoRawCredentials(contentChanged);
  }
}
