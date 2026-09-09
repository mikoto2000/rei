package dev.mikoto2000.rei.conversation;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.command.UserInputService;
import dev.mikoto2000.rei.core.command.UserInputParser;
import dev.mikoto2000.rei.ui.shell.HistoryCommand;
import picocli.CommandLine;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HistoryShellCommandTest {
  @TempDir Path temp;
  String prior;
  ProjectRegistry registry;
  ProjectContext a, b;
  ProjectService projects;
  ConversationLogStore logs;
  ConversationHistorySearchService search;
  CommandLine command;
  java.io.StringWriter output;
  @BeforeEach void setup() throws Exception {
    prior = System.getProperty("rei.data-dir");
    System.setProperty("rei.data-dir", temp.resolve("data").toString());
    registry = new ProjectRegistry(temp.resolve("data/projects.json"));
    a = registry.resolve(Files.createDirectory(temp.resolve("Alpha")));
    b = registry.resolve(Files.createDirectory(temp.resolve("MaCa Editor")));
    projects = new ProjectService(a.root(), registry);
    logs = spy(new ConversationLogStore());
    var ds = new SQLiteDataSource(); ds.setUrl("jdbc:sqlite:" + temp.resolve("unused.db"));
    search = spy(new ConversationHistorySearchService(ds, logs));
    command = new CommandLine(new HistoryCommand(new HistoryShellService(projects, logs, search)));
    output = new java.io.StringWriter();
    command.setOut(new java.io.PrintWriter(output)); command.setErr(new java.io.PrintWriter(output));
  }
  @AfterEach void cleanup() { if (prior == null) System.clearProperty("rei.data-dir"); else System.setProperty("rei.data-dir", prior); }
  String run(String... args) {
    output.getBuffer().setLength(0);
    assertThat(command.execute(args)).isZero();
    return output.toString();
  }
  void add(ProjectContext project, String id, String role, String text) { logs.append(project.conversationId(id), role, text); }
  @Test void bareHistoryExactlyMatchesDefaultShowAndLastLimits() {
    for (int i=0; i<65; i++) add(a,"chat:main","user","message-"+i);
    add(b,"chat:main","user","foreign");
    String bare=run();
    assertThat(bare).isEqualTo(run("show")).contains("Project: Alpha", "Conversation: chat:main", "message-15", "message-64")
        .doesNotContain("message-14", "foreign", "Usage:");
    assertThat(run("show","--last","2")).contains("message-63", "message-64").doesNotContain("message-62");
    assertThat(run("show","--all")).contains("message-0", "message-64");
    verify(logs, never()).readProject(anyString());
    verify(logs, never()).readAll();
  }
  @Test void listUsesProjectMetadataAndSupportsPagingAndEmptyResults() {
    add(a,"chat:main","user","one"); add(a,"chat:main","assistant","two"); add(b,"chat:foreign","user","three");
    assertThat(run("list")).contains("Alpha", "chat:main", "2", "UPDATED", "MESSAGES").doesNotContain("chat:foreign");
    assertThat(run("list","--project",b.id())).contains("chat:foreign").doesNotContain("chat:main");
    assertThat(run("list","--limit","1","--offset","1")).contains("No conversations");
  }
  @Test void explicitProjectAndConversationAreReadWithoutSwitchingShell() {
    add(b,"chat:test","tool","tool output"); add(b,"chat:main","assistant","default B");
    assertThat(run("show","chat:test","--project","MaCa Editor")).contains("tool", "tool output", "chat:test");
    assertThat(run("show","--project",b.id())).contains("default B");
    assertThat(projects.currentContext().id()).isEqualTo(a.id());
  }
  @Test void displaysAllPersistedRolesAndInterventionsWithRedactionAndTruncation() {
    for (String role : List.of("system","user","assistant","tool","user")) add(a,"chat:main",role,role+" message");
    add(a,"chat:main","tool","Authorization: Bearer SECRET_VALUE\n"+"payload".repeat(600));
    assertThat(run()).contains("] system", "] user", "] assistant", "] tool", "[REDACTED]", "(truncated)").doesNotContain("SECRET_VALUE");
  }
  @Test void searchUsesExistingScopesAndDisplaysProvenance() {
    add(a,"chat:main","user","Working Set local"); add(b,"chat:test","user","Working Set foreign");
    assertThat(run("search","Working","Set")).contains("Alpha", "MaCa Editor", "chat:test", "foreign");
    assertThat(run("search","--current","Working Set")).contains("local").doesNotContain("foreign");
    assertThat(run("search","--all","--limit","1","Working Set")).contains("1.").doesNotContain("2.");
    verify(search).searchForShell(argThat(r -> r.retrievalScope()==HistorySearchScope.CURRENT_PROJECT_PREFERRED && r.preferredProjectId().equals(a.id())));
    verify(search).searchForShell(argThat(r -> r.retrievalScope()==HistorySearchScope.CURRENT_PROJECT_ONLY));
    verify(search).searchForShell(argThat(r -> r.retrievalScope()==HistorySearchScope.ALL_PROJECTS && r.limit()==1));
  }
  @Test void controlsUseShellBWhileRunScopeAExistsAndDoNotTouchActiveRuns() {
    add(a,"chat:main","user","A only"); add(b,"chat:main","user","B needle");
    var tasks = new ArrayList<Runnable>();
    var router = new ConversationInputRouter(tasks::add,(c,p,q)->assertThat(q.drain()).isEmpty());
    router.submit(a.root(),a.conversationId("chat:main"),"A work");
    router.submit(b.root(),b.conversationId("chat:main"),"B work");
    var before=router.activeRuns(); projects.cd(b.root().toString());
    try(var scope=AgentRunScope.open(new AgentRunContext("run-a",a,"chat:main"))) {
      for(String text : List.of("/history","/history show","/history search --current needle")) {
        var input=new UserInputService(new UserInputParser()).interpret(text);
        assertThat(input.kind()).isEqualTo(UserInputService.Kind.COMMAND);
        assertThat(run(Arrays.copyOfRange(input.arguments(),1,input.arguments().length))).contains("MaCa Editor").doesNotContain("A only");
      }
    }
    assertThat(router.activeRuns()).isEqualTo(before);
    tasks.forEach(Runnable::run);
    assertThat(router.activeRuns()).isEmpty();
  }
  @Test void handlesUsageAndResolutionErrorsWithoutStackTraces() throws Exception {
    for(String[] args : List.of(new String[]{"search"}, new String[]{"search","--limit","abc","q"},
        new String[]{"search","--limit","0","q"},new String[]{"show","--last","-1"},new String[]{"show","--last","abc"},
        new String[]{"show","--all","--last","5"},new String[]{"search","--current","--all","q"},
        new String[]{"show","chat:missing"},new String[]{"list","--project","missing"})) {
      output.getBuffer().setLength(0); assertThat(command.execute(args)).isNotZero();
      assertThat(output.toString()).doesNotContain("Exception", "\tat ");
    }
    registry.resolve(Files.createDirectories(temp.resolve("duplicate/Alpha")));
    output.getBuffer().setLength(0);
    assertThat(command.execute("list","--project","Alpha")).isNotZero();
    assertThat(output.toString()).contains("Ambiguous project");
    assertThat(run("--help")).contains("show", "search", "list");
  }
  @Test void searchStreamsStoresAndRedactsBeforeCreatingSnippetsWithoutPublishingEvents() {
    add(a,"chat:main","user","token="+"SECRET".repeat(200)+" needle");
    doThrow(new AssertionError("Must not load all history")).when(logs).readProject(anyString());
    var publisher=mock(dev.mikoto2000.rei.event.AgentEventPublisher.class);
    search.setHistoryEvents(new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()),publisher);
    assertThat(run("search","--current","needle")).contains("needle", "[REDACTED]").doesNotContain("SECRET");
    verifyNoInteractions(publisher);
  }
}
