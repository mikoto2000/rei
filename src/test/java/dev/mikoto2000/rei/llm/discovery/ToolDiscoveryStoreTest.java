package dev.mikoto2000.rei.llm.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.durable.DurableTaskStore;
import dev.mikoto2000.rei.review.ReviewCoverageLedger;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteDataSource;

class ToolDiscoveryStoreTest {
  @TempDir Path dir;

  private AgentRunContext owner(String run) {
    return new AgentRunContext(
        run,
        "session",
        dir,
        "project",
        AgentRunContext.RequestSource.SHELL,
        AgentRunContext.Mode.EXCLUSIVE);
  }

  private SQLiteDataSource source() {
    var s = new SQLiteDataSource();
    s.setUrl("jdbc:sqlite:" + dir.resolve("test.db"));
    return s;
  }

  private ToolDiscoveryStore store(SQLiteDataSource s) {
    var b = new DefaultListableBeanFactory();
    return new ToolDiscoveryStore(
        s,
        b.getBeanProvider(ReviewCoverageLedger.class),
        b.getBeanProvider(DurableTaskStore.class));
  }

  @Test
  void externalReviewRetentionFollowsTheRealLedgerAndRejectsAnotherOwner() {
    var source = source();
    var store = store(source);
    var reviews =
        new dev.mikoto2000.rei.externalagent.ExternalReviewRepository(
            source, java.time.Clock.systemUTC());
    reviews.start(owner("run"), "review", dir, "target", null);
    assertThat(store.required(owner("run"), "external-codex-review", "review")).isTrue();
    reviews.finish(
        "project",
        "review",
        dev.mikoto2000.rei.externalagent.ExternalAgentResult.rejected("cancelled"));
    assertThat(store.required(owner("run"), "external-codex-review", "review")).isFalse();
    var other =
        new AgentRunContext(
            "other",
            "other-session",
            dir,
            "project",
            AgentRunContext.RequestSource.SHELL,
            AgentRunContext.Mode.EXCLUSIVE);
    assertThat(store.required(other, "external-codex-review", "review")).isTrue();
  }

  @Test
  void anUncertainDispatchCannotBeReleasedByAnUnrelatedCallInTheSameSet() throws Exception {
    var props = new ToolDiscoveryProperties();
    props.setEnabled(true);
    props.setMaxActiveTools(39);
    var discovery =
        new ToolDiscovery(
            props, new DefaultListableBeanFactory().getBeanProvider(ToolDiscoveryStore.class));
    var run = ToolDiscoveryLoopTest.execution("uncertain");
    var initial = ToolDiscoveryLoopTest.prompt(run);
    var session = discovery.start(initial, run, c -> c);
    var outbound = session.project(initial);
    var search =
        ((org.springframework.ai.model.tool.ToolCallingChatOptions) outbound.getOptions())
            .getToolCallbacks().stream()
                .filter(c -> c.getToolDefinition().name().equals("searchTools"))
                .findFirst()
                .orElseThrow();
    assertThat(search.call("{\"query\":\"proposeTextChangeSet\",\"maxResults\":1}"))
        .contains("\"status\":\"added\"");
    session.beforeCall("proposeTextChangeSet", "{}", "unknown-call");
    session.afterCall("inspectTextChangeSet", "{\"id\":\"unrelated\"}", "{}", "other-call");
    assertThat(search.call("{\"query\":\"googleCalendarListEventsForDate\",\"maxResults\":1}"))
        .contains("capacity_exceeded");
  }

  @Test
  void persistsVisibilityAndRebindsOnlyThroughOwnedCheckpointLink() {
    var store = store(source());
    var saved =
        new ToolDiscoveryStore.Saved(
            new RunToolAvailability.Saved(Map.of("calendar", 1L), 1, 1),
            Map.of("now", "hash"),
            "endpoint",
            Map.of(),
            Set.of());
    store.save(owner("old"), saved);
    store.inherit(owner("new"), "old");
    assertThat(store.load(owner("new"))).contains(saved);
    var other =
        new AgentRunContext(
            "new",
            "other-session",
            dir,
            "project",
            AgentRunContext.RequestSource.SHELL,
            AgentRunContext.Mode.EXCLUSIVE);
    assertThat(store.load(other)).isEmpty();
    assertThatThrownBy(() -> store.save(other, saved)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void retainsUnknownSingleChangesAndDoesNotReconcileWhileReading() {
    var source = source();
    var store = store(source);
    var db = JdbcClient.create(source);
    db.sql("CREATE TABLE text_change_sets(id TEXT,project_id TEXT,root TEXT,status TEXT)").update();
    db.sql("INSERT INTO text_change_sets VALUES('change','project',?,'UNKNOWN')")
        .param(dir.toString())
        .update();
    assertThat(store.required(owner("run"), "text-change", "change")).isTrue();
    assertThat(db.sql("SELECT status FROM text_change_sets").query(String.class).single())
        .isEqualTo("UNKNOWN");
    db.sql("UPDATE text_change_sets SET status='APPLIED'").update();
    assertThat(store.required(owner("run"), "text-change", "change")).isFalse();
  }

  @Test
  void appliedDocumentWithStagingIsNotFinishedUntilCleanup() {
    var source = source();
    var store = store(source);
    var db = JdbcClient.create(source);
    db.sql(
            "CREATE TABLE text_document_change_sets(id TEXT,project TEXT,root TEXT,session"
                + " TEXT,status TEXT,phase TEXT,stages TEXT)")
        .update();
    db.sql(
            "INSERT INTO text_document_change_sets"
                + " VALUES('document','project',?,'session','APPLIED','VERIFIED','[{}]')")
        .param(dir.toString())
        .update();
    assertThat(store.required(owner("run"), "document-change", "document")).isTrue();
    db.sql("UPDATE text_document_change_sets SET phase='CLEANED',stages='[]'").update();
    assertThat(store.required(owner("run"), "document-change", "document")).isFalse();
  }

  @Test
  void rejectsChangedEndpointBeforeSendingTheResumedRequest() throws Exception {
    var store = store(source());
    var b = new DefaultListableBeanFactory();
    b.registerSingleton("store", store);
    var props = new ToolDiscoveryProperties();
    props.setEnabled(true);
    var discovery = new ToolDiscovery(props, b.getBeanProvider(ToolDiscoveryStore.class));
    var execution = ToolDiscoveryLoopTest.execution("resume");
    execution.setRunContext(owner("resume"));
    var initial = ToolDiscoveryLoopTest.prompt(execution);
    discovery.start(initial, execution, c -> c).project(initial);
    var changed =
        new org.springframework.ai.chat.prompt.Prompt(
            "continue",
            ((org.springframework.ai.openai.OpenAiChatOptions) initial.getOptions())
                .mutate()
                .model("different-model")
                .build());
    assertThatThrownBy(() -> discovery.start(changed, execution, c -> c))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("endpoint");
  }

  @Test
  void changedDefinitionCannotReuseTheOldApprovalSnapshot() throws Exception {
    var store = store(source());
    var b = new DefaultListableBeanFactory();
    b.registerSingleton("store", store);
    var props = new ToolDiscoveryProperties();
    props.setEnabled(true);
    var discovery = new ToolDiscovery(props, b.getBeanProvider(ToolDiscoveryStore.class));
    var run = ToolDiscoveryLoopTest.execution("schema");
    run.setRunContext(owner("schema"));
    var initial = ToolDiscoveryLoopTest.prompt(run);
    discovery.start(initial, run, c -> c).project(initial);
    var callbacks =
        new java.util.ArrayList<>(
            ((org.springframework.ai.model.tool.ToolCallingChatOptions) initial.getOptions())
                .getToolCallbacks());
    callbacks.replaceAll(c ->
        c.getToolDefinition().name().equals("now")
            ? new org.springframework.ai.tool.ToolCallback() {
              public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return org.springframework.ai.tool.definition.ToolDefinition.builder()
                    .name("now")
                    .description("changed")
                    .inputSchema(
                        "{\"type\":\"object\",\"properties\":{\"newField\":{\"type\":\"string\"}}}")
                    .build();
              }

              public String call(String input) {
                throw new AssertionError("Must not execute changed tool");
              }
            }
            : c);
    var changed =
        new org.springframework.ai.chat.prompt.Prompt(
            "continue",
            ((org.springframework.ai.openai.OpenAiChatOptions) initial.getOptions())
                .mutate()
                .toolCallbacks(callbacks)
                .build());
    assertThatThrownBy(() -> discovery.start(changed, run, c -> c))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("definition");
  }
}
