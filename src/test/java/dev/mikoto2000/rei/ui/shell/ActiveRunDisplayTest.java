package dev.mikoto2000.rei.ui.shell;

import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.project.*;
import static org.assertj.core.api.Assertions.*;

class ActiveRunDisplayTest {
  @TempDir Path temp;
  @Test void runsCommandAndPromptShowAllProjectsAfterSwitchAndCompletion() throws Exception {
    var registry = new ProjectRegistry(temp.resolve("projects.json"));
    var a = registry.resolve(Files.createDirectory(temp.resolve("Alpha")));
    var b = registry.resolve(Files.createDirectory(temp.resolve("Beta")));
    var projects = new ProjectService(a.root(), registry);
    var tasks = new ArrayList<Runnable>();
    var router = new ConversationInputRouter(tasks::add, (c,p,q) -> {});
    var display = new ActiveRunDisplay(router, projects, Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC));
    router.submit(a.root(), a.conversationId("chat:main"), "request A");
    projects.cd(b.root().toString());
    router.submit(b.root(), b.conversationId("chat:main"), "request B");
    assertThat(display.promptStatus()).isEqualTo("[Beta] [2 running]");
    var output = new java.io.StringWriter();
    var command = new picocli.CommandLine(new RunsCommand(display));
    command.setOut(new java.io.PrintWriter(output));
    assertThat(command.execute()).isZero();
    assertThat(output.toString()).contains("Alpha", "Beta", "RUNNING", "request A", "request B", "00:00");
    assertThat(router.activeRuns()).extracting(ActiveRun::projectId).containsExactlyInAnyOrder(a.id(), b.id());
    tasks.getFirst().run();
    assertThat(display.promptStatus()).isEqualTo("[Beta] [1 running]");
    tasks.get(1).run();
    assertThat(display.promptStatus()).isEqualTo("[Beta] [0 running]");
    assertThat(display.rows()).isEmpty();
  }

  @Test void duplicateNamesAreDisambiguatedAndPastEventsDoNotBecomeActive() throws Exception {
    var registry = new ProjectRegistry(temp.resolve("projects.json"));
    var a = registry.resolve(Files.createDirectories(temp.resolve("one/rei")));
    var b = registry.resolve(Files.createDirectories(temp.resolve("two/rei")));
    var projects = new ProjectService(a.root(), registry);
    var router = new ConversationInputRouter(new ArrayList<Runnable>()::add, (c,p,q) -> {});
    var display = new ActiveRunDisplay(router, projects, Clock.systemUTC());
    var events = new dev.mikoto2000.rei.event.ProjectAgentEventStore(temp);
    events.append(new dev.mikoto2000.rei.event.AgentEventFactory(Clock.systemUTC()).runStarted("past", "test", null)
        .withOwnership(new AgentRunContext("past", a, "chat:main")));
    assertThat(display.rows()).isEmpty();
    router.submit(a.root(), a.conversationId("chat:main"), "A");
    router.submit(b.root(), b.conversationId("chat:main"), "B");
    assertThat(display.rows()).anyMatch(row -> row.contains(a.id().substring(0,8)))
        .anyMatch(row -> row.contains(b.id().substring(0,8)));
  }
}
