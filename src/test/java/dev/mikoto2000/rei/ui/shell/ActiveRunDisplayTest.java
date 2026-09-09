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
  @Test void includesEveryExecutionTypeInRowsAndPrompt() throws Exception {
    var registry=new ProjectRegistry(temp.resolve("projects.json"));
    var a=registry.resolve(Files.createDirectory(temp.resolve("A")));
    var b=registry.resolve(Files.createDirectory(temp.resolve("B")));
    var projects=new ProjectService(a.root(),registry);
    var tasks=new ArrayList<Runnable>();
    var router=new ConversationInputRouter(tasks::add,(c,p,q)->{});
    var display=new ActiveRunDisplay(router,projects,Clock.systemUTC());
    router.submit(a.root(),a.conversationId("chat:main"),"chat work");
    router.submitBackground(a,dev.mikoto2000.rei.core.execution.ExecutionType.SUMMARIZE,"https://example.com",e->{});
    router.submitBackground(b,dev.mikoto2000.rei.core.execution.ExecutionType.IMAGE,"picture",e->{});
    assertThat(display.promptStatus()).isEqualTo("[A] [3 running]");
    assertThat(String.join("\n",display.rows())).contains("AGENT", "SUMMARIZE", "IMAGE", "picture", "https://example.com");
    tasks.getFirst().run(); assertThat(display.promptStatus()).isEqualTo("[A] [2 running]");
    tasks.get(1).run(); tasks.get(2).run(); assertThat(display.promptStatus()).isEqualTo("[A] [0 running]");
  }
  @Test void runsCommandAndPromptShowAllProjectsAfterSwitchAndCompletion() throws Exception {
    var registry = new ProjectRegistry(temp.resolve("projects.json"));
    var a = registry.resolve(Files.createDirectory(temp.resolve("Alpha")));
    var b = registry.resolve(Files.createDirectory(temp.resolve("Beta")));
    var projects = new ProjectService(a.root(), registry);
    var tasks = new ArrayList<Runnable>();
    var router = new ConversationInputRouter(tasks::add, (c,p,q) -> {});
    var display = new ActiveRunDisplay(router, projects, Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC));
    router.submit(a.root(), a.conversationId("chat:main"), "日本語の依頼 A");
    projects.cd(b.root().toString());
    router.submit(b.root(), b.conversationId("chat:main"), "request B");
    assertThat(display.promptStatus()).isEqualTo("[Beta] [2 running]");
    var output = new java.io.StringWriter();
    var command = new picocli.CommandLine(picocli.CommandLine.Model.CommandSpec.create().name("rei"));
    command.addSubcommand("runs", new RunsCommand(display));
    command.setOut(new java.io.PrintWriter(output));
    var completer = dev.mikoto2000.rei.core.command.ReiLineReaderFactory.completer(command);
    completer.complete(org.mockito.Mockito.mock(org.jline.reader.LineReader.class),
        new org.jline.reader.impl.DefaultParser().parse("/runs ", 6), new ArrayList<>());
    assertThat(command.execute("runs")).isZero();
    assertThat(output.toString()).contains("Alpha", "Beta", "RUNNING", "日本語の依頼 A", "request B", "00:00");
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
