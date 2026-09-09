package dev.mikoto2000.rei.conversation;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.core.chat.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectHistoryRetrievalTest {
  @TempDir Path temp;
  String prior;
  ProjectRegistry registry;
  ProjectService projects;
  ProjectContext a, b, c;
  ConversationLogStore logs;
  ConversationHistorySearchService service;

  @BeforeEach void setup() throws Exception {
    prior = System.getProperty("rei.data-dir");
    System.setProperty("rei.data-dir", temp.resolve("data").toString());
    registry = new ProjectRegistry(temp.resolve("data/projects.json"));
    a = registry.resolve(Files.createDirectory(temp.resolve("Alpha")));
    b = registry.resolve(Files.createDirectory(temp.resolve("MaCa Editor")));
    c = registry.resolve(Files.createDirectory(temp.resolve("Other")));
    projects = new ProjectService(a.root(), registry);
    logs = spy(new ConversationLogStore());
    var ds = new SQLiteDataSource(); ds.setUrl("jdbc:sqlite:" + temp.resolve("db"));
    service = new ConversationHistorySearchService(ds, logs);
  }
  @AfterEach void cleanup() {
    if (prior == null) System.clearProperty("rei.data-dir"); else System.setProperty("rei.data-dir", prior);
  }
  void add(ProjectContext p, String text) { logs.append(p.conversationId("chat:main"), "user", text); }
  HistorySearchRequest request(String query, HistorySearchScope scope, String reference) {
    return new HistorySearchRequest(query, a.id(), reference, scope, "chat", null, null, null, 50);
  }

  @Test void strongCurrentResultsPreventOtherStoreReads() {
    for (int i = 0; i < 3; i++) add(a, "working set policy " + i);
    add(b, "working set policy newer");
    var found = service.search(request("working set policy", HistorySearchScope.CURRENT_PROJECT_PREFERRED, null));
    assertThat(found).hasSize(3).allMatch(r -> r.sourceProjectId().equals(a.id()));
    verify(logs, never()).readProject(b.id());
    verify(logs, never()).readProject(c.id());
  }
  @Test void weakCurrentResultsAllowRelevantFallbackButCurrentComesFirst() {
    add(a, "working set"); add(b, "working set policy decisions"); add(c, "unrelated weather");
    var found = service.search(request("working set policy decisions", HistorySearchScope.CURRENT_PROJECT_PREFERRED, null));
    assertThat(found).extracting(ConversationSearchResult::sourceProjectId).containsExactly(a.id(), b.id());
  }
  @Test void missingCurrentResultsFallBackWithStrictCrossProjectBudget() {
    for (int i = 0; i < 10; i++) { add(b, "needle " + i); add(c, "needle " + i); }
    var found = service.search(request("needle", HistorySearchScope.CURRENT_PROJECT_PREFERRED, null));
    assertThat(found).hasSize(3).allMatch(r -> !r.sourceProjectId().equals(a.id()));
  }
  @Test void explicitProjectWinsEvenWhenCurrentHasEnoughMatches() {
    for (int i = 0; i < 4; i++) add(a, "integration policy " + i);
    add(b, "integration policy"); add(c, "integration policy");
    var found = service.search(request("integration policy", HistorySearchScope.CURRENT_PROJECT_PREFERRED, "maca editor"));
    assertThat(found.getFirst().sourceProjectId()).isEqualTo(b.id());
    assertThat(found).noneMatch(r -> r.sourceProjectId().equals(c.id()));
  }
  @Test void safeMentionResolutionRemovesProjectNameFromKeywordQuery() {
    add(b, "integration policy");
    var found = service.search(request("MaCa Editor integration policy", HistorySearchScope.CURRENT_PROJECT_PREFERRED, null));
    assertThat(found).hasSize(1);
    assertThat(found.getFirst().sourceProjectId()).isEqualTo(b.id());
  }
  @Test void ambiguousAndUnknownExplicitNamesAreRejected() throws Exception {
    registry.resolve(Files.createDirectories(temp.resolve("nested/MaCa Editor")));
    assertThatThrownBy(() -> service.search(request("needle", HistorySearchScope.CURRENT_PROJECT_PREFERRED, "MaCa Editor")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.search(request("needle", HistorySearchScope.CURRENT_PROJECT_PREFERRED, "missing")))
        .isInstanceOf(IllegalArgumentException.class);
  }
  @Test void currentOnlyNeverReadsForeignStoreEvenWithExplicitReference() {
    add(a, "needle A"); add(b, "needle B");
    var found = service.search(request("needle", HistorySearchScope.CURRENT_PROJECT_ONLY, "MaCa Editor"));
    assertThat(found).extracting(ConversationSearchResult::sourceProjectId).containsExactly(a.id());
    verify(logs, never()).readProject(b.id());
  }
  @Test void allProjectsRanksByRelevanceWithoutCurrentBoost() {
    add(a, "working set"); add(b, "working set policy decisions");
    var found = service.search(request("working set policy decisions", HistorySearchScope.ALL_PROJECTS, null));
    assertThat(found).extracting(ConversationSearchResult::sourceProjectId).containsExactly(b.id(), a.id());
    verify(logs).readProject(c.id());
  }
  @Test void defaultUsesCurrentPreferredAndRunKeepsOwnershipAcrossSwitchAndIntervention() {
    for (int i = 0; i < 3; i++) { add(a, "needle A " + i); add(b, "needle B " + i); }
    var run = new AgentRunContext("run-a", a, "chat:main");
    try (var ignored = AgentRunScope.open(run)) {
      projects.cd(b.root().toString());
      var mailbox = new UserInterventionQueue(); mailbox.offer("needle");
      assertThat(service.search(mailbox.drain().getFirst(), "chat", null, null, null, 10))
          .allMatch(r -> r.sourceProjectId().equals(a.id()));
    }
    assertThat(service.search("needle", "chat", null, null, null, 10))
        .allMatch(r -> r.sourceProjectId().equals(b.id()));
  }
  @Test void foreignResultAndDetailCarryBoundaryInActualJsonWithoutChangingFilesystemContext() throws Exception {
    add(b, "needle src-tauri/src/ai/openai.rs branch develop build cargo");
    var result = service.search(request("needle", HistorySearchScope.CURRENT_PROJECT_PREFERRED, null)).getFirst();
    String json = new ObjectMapper().writeValueAsString(result);
    assertThat(json).contains(b.id(), "MaCa Editor", "another project", "file paths");
    var detail = service.detail(result.conversationId(), 100);
    assertThat(detail.sourceProjectId()).isEqualTo(b.id());
    assertThat(new ObjectMapper().writeValueAsString(detail)).contains("another project", "file paths");
    assertThat(projects.currentContext()).isEqualTo(a);
  }
  @Test void foreignDetailCannotBypassBudget() {
    for (int i = 0; i < 12; i++) add(b, "needle " + "x".repeat(1000));
    var detail = service.detail(b.conversationId("chat:main"), 100);
    assertThat(detail.messages()).hasSize(3).allMatch(m -> m.content().length() <= 500);
  }
  @Test void storageStaysIsolatedAcrossSwitchesAndIdenticalLogicalIds() {
    add(a, "A only"); projects.cd(b.root().toString()); add(b, "B only");
    assertThat(logs.readAll()).extracting(ConversationLogEntry::content).containsExactly("B only");
    projects.cd(a.root().toString());
    assertThat(logs.readAll()).extracting(ConversationLogEntry::content).containsExactly("A only");
    assertThat(a.conversationId("chat:main")).isNotEqualTo(b.conversationId("chat:main"));
  }

  @Test void toolSchemaAndReturnedContextExposeScopeProjectAndBoundary() throws Exception {
    add(b, "needle src/foreign.rs");
    var callback = Arrays.stream(org.springframework.ai.support.ToolCallbacks.from(new ConversationHistoryTools(service)))
        .filter(t -> t.getToolDefinition().name().equals("searchConversationHistory")).findFirst().orElseThrow();
    assertThat(callback.getToolDefinition().inputSchema()).contains("retrievalScope", "referencedProject");
    String json = callback.call("{\"query\":\"needle\",\"scope\":\"chat\",\"retrievalScope\":\"CURRENT_PROJECT_PREFERRED\",\"referencedProject\":\"MaCa Editor\"}");
    assertThat(json).contains(b.id(), "MaCa Editor", "another project", "file paths");
    verify(logs, never()).append(eq(a.conversationId("chat:main")), anyString(), anyString());
    assertThat(projects.currentContext()).isEqualTo(a);
  }

  @Test void searchEventHasCountsAndRunOwnershipButNoConversationText() throws Exception {
    add(a, "needle private-message-A"); add(b, "needle private-message-B");
    var bus = new dev.mikoto2000.rei.event.InMemoryAgentEventBus();
    var captured = new ArrayList<dev.mikoto2000.rei.event.AgentEvent>();
    bus.subscribe(captured::add);
    service.setHistoryEvents(new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()), bus);
    try (var ignored = AgentRunScope.open(new AgentRunContext("run-a", a, "chat:main"))) {
      projects.cd(b.root().toString());
      service.search("needle", "chat", null, null, null, 10);
    }
    var event = captured.getFirst();
    assertThat(event.type()).isEqualTo(dev.mikoto2000.rei.event.AgentEventType.HISTORY_SEARCH_COMPLETED);
    assertThat(event.runId()).isEqualTo("run-a");
    assertThat(event.projectId()).isEqualTo(a.id());
    var payload = (dev.mikoto2000.rei.event.HistorySearchCompletedPayload) event.payload();
    assertThat(payload.preferredProjectId()).isEqualTo(a.id());
    assertThat(payload.currentProjectHitCount()).isEqualTo(1);
    assertThat(payload.crossProjectHitCount()).isEqualTo(1);
    assertThat(payload.searchedProjectCount()).isEqualTo(3);
    var store = new dev.mikoto2000.rei.event.ProjectAgentEventStore(temp.resolve("events-data"));
    store.append(event);
    assertThat(store.recent(a.id(), 1).getFirst().payload()).isEqualTo(payload);
    assertThat(new ObjectMapper().writeValueAsString(payload)).doesNotContain("private-message", "needle");
  }

  @Test void filtersApplyToFallbackAndResultBudgetCannotBeRaisedByCaller() {
    for (int i = 0; i < 20; i++) { add(a, "needle " + i); add(b, "needle " + i); }
    var result = service.search(request("needle", HistorySearchScope.ALL_PROJECTS, null));
    assertThat(result.stream().filter(r -> r.sourceProjectId().equals(a.id())).count()).isEqualTo(8);
    assertThat(result.stream().filter(r -> !r.sourceProjectId().equals(a.id())).count()).isEqualTo(3);
    assertThat(service.search(new HistorySearchRequest("needle", a.id(), null, HistorySearchScope.ALL_PROJECTS,
        "chat", "assistant", null, null, 50))).isEmpty();
    assertThat(service.search(new HistorySearchRequest("needle", a.id(), null, HistorySearchScope.ALL_PROJECTS,
        "chat", null, null, "2000-01-01", 50))).isEmpty();
  }

  @Test void unknownProjectDetailDoesNotReadArbitraryStores() {
    String id = UUID.randomUUID().toString();
    assertThatThrownBy(() -> service.detail("project:" + id + ":chat:main", 10)).isInstanceOf(IllegalArgumentException.class);
    verify(logs, never()).readProject(id);
  }

  @Test void equalRelevanceUsesActualInstantOrderIncludingFractionalSeconds() {
    doReturn(List.of(
        new ConversationLogEntry(a.conversationId("chat:main"), "chat", "user", java.time.OffsetDateTime.parse("2026-09-01T00:00:00Z"), "needle older"),
        new ConversationLogEntry(a.conversationId("chat:main"), "chat", "user", java.time.OffsetDateTime.parse("2026-09-01T00:00:00.100Z"), "needle newer")))
        .when(logs).readProject(a.id());
    assertThat(service.search(request("needle", HistorySearchScope.CURRENT_PROJECT_ONLY, null)))
        .extracting(ConversationSearchResult::content).containsExactly("needle newer", "needle older");
  }

  @Test void asynchronousToolRestoresRunOwnerForInterventionAfterShellSwitch() throws Exception {
    for (int i = 0; i < 3; i++) { add(a, "needle A " + i); add(b, "needle B " + i); }
    var run = new AgentRunContext("run-a", a, "chat:main");
    var tool = Arrays.stream(org.springframework.ai.support.ToolCallbacks.from(new ConversationHistoryTools(service)))
        .filter(t -> t.getToolDefinition().name().equals("searchConversationHistory")).findFirst().orElseThrow();
    var decorated = new dev.mikoto2000.rei.event.ToolEventCallbackDecorator(tool,
        new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()), event -> {});
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var mailbox = new UserInterventionQueue();
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var future = executor.submit(() -> {
        assertThat(AgentRunScope.current()).isNull();
        entered.countDown();
        if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release timeout");
        String input = new ObjectMapper().writeValueAsString(Map.of("query", mailbox.drain().getFirst()));
        String result = decorated.call(input, new org.springframework.ai.chat.model.ToolContext(Map.of(AgentRunContext.class.getName(), run)));
        assertThat(AgentRunScope.current()).isNull();
        return result;
      });
      try {
        assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        mailbox.offer("needle");
        projects.cd(b.root().toString());
        release.countDown();
        String result = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(result).contains(a.id(), "needle A").doesNotContain(b.id(), "needle B");
        assertThat(projects.currentContext()).isEqualTo(b);
      } finally { release.countDown(); }
    }
  }
}
