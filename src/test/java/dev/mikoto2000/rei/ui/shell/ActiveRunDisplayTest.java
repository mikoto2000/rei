package dev.mikoto2000.rei.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.chat.ActiveRun;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.project.ProjectService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 活動中実行表示の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ActiveRunDisplayTest extends dev.mikoto2000.rei.core.project.ProjectClientTestSupport {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path temp;

  /** ターミナルでの対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void includesEveryExecutionTypeInRowsAndPrompt() throws Exception {
    var registry = new ProjectRegistry(temp.resolve("projects.json"));
    var a = registry.resolve(Files.createDirectory(temp.resolve("A")));
    var b = registry.resolve(Files.createDirectory(temp.resolve("B")));
    var projects = connect(new ProjectService(a.root(), registry));
    var tasks = new ArrayList<Runnable>();
    var router = new ConversationInputRouter(tasks::add, (c, p, q) -> {});
    var display = new ActiveRunDisplay(router, projects, Clock.systemUTC());
    router.submit(a.root(), a.conversationId("chat:main"), "chat work");
    router.submitBackground(
        a,
        dev.mikoto2000.rei.core.execution.ExecutionType.SUMMARIZE,
        "https://example.com",
        e -> {});
    router.submitBackground(
        b, dev.mikoto2000.rei.core.execution.ExecutionType.IMAGE, "picture", e -> {});
    assertThat(display.promptStatus()).isEqualTo("[A] [3 running]");
    assertThat(String.join("\n", display.rows()))
        .contains("AGENT", "SUMMARIZE", "IMAGE", "picture", "https://example.com");
    tasks.getFirst().run();
    assertThat(display.promptStatus()).isEqualTo("[A] [2 running]");
    tasks.get(1).run();
    tasks.get(2).run();
    assertThat(display.promptStatus()).isEqualTo("[A] [0 running]");
  }

  /** ターミナルでの対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void runsCommandAndPromptShowAllProjectsAfterSwitchAndCompletion() throws Exception {
    var registry = new ProjectRegistry(temp.resolve("projects.json"));
    var a = registry.resolve(Files.createDirectory(temp.resolve("Alpha")));
    var b = registry.resolve(Files.createDirectory(temp.resolve("Beta")));
    var projects = connect(new ProjectService(a.root(), registry));
    var tasks = new ArrayList<Runnable>();
    var router = new ConversationInputRouter(tasks::add, (c, p, q) -> {});
    var display =
        new ActiveRunDisplay(
            router, projects, Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC));
    router.submit(a.root(), a.conversationId("chat:main"), "日本語の依頼 A");
    projects.cd(b.root().toString());
    router.submit(b.root(), b.conversationId("chat:main"), "request B");
    assertThat(display.promptStatus()).isEqualTo("[Beta] [2 running]");
    var output = new java.io.StringWriter();
    var command =
        new picocli.CommandLine(picocli.CommandLine.Model.CommandSpec.create().name("rei"));
    command.addSubcommand("runs", new RunsCommand(display));
    command.setOut(new java.io.PrintWriter(output));
    var completer = dev.mikoto2000.rei.core.command.ReiLineReaderFactory.completer(command);
    completer.complete(
        org.mockito.Mockito.mock(org.jline.reader.LineReader.class),
        new org.jline.reader.impl.DefaultParser().parse("/runs ", 6),
        new ArrayList<>());
    assertThat(command.execute("runs")).isZero();
    assertThat(output.toString())
        .contains("Alpha", "Beta", "RUNNING", "日本語の依頼 A", "request B", "00:00");
    assertThat(router.activeRuns())
        .extracting(ActiveRun::projectId)
        .containsExactlyInAnyOrder(a.id(), b.id());
    tasks.getFirst().run();
    assertThat(display.promptStatus()).isEqualTo("[Beta] [1 running]");
    tasks.get(1).run();
    assertThat(display.promptStatus()).isEqualTo("[Beta] [0 running]");
    assertThat(display.rows()).isEmpty();
  }

  /** ターミナルでの対話で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void duplicateNamesAreDisambiguatedAndPastEventsDoNotBecomeActive() throws Exception {
    var registry = new ProjectRegistry(temp.resolve("projects.json"));
    var a = registry.resolve(Files.createDirectories(temp.resolve("one/rei")));
    var b = registry.resolve(Files.createDirectories(temp.resolve("two/rei")));
    var projects = connect(new ProjectService(a.root(), registry));
    var router = new ConversationInputRouter(new ArrayList<Runnable>()::add, (c, p, q) -> {});
    var display = new ActiveRunDisplay(router, projects, Clock.systemUTC());
    var events = new dev.mikoto2000.rei.event.ProjectAgentEventStore(temp);
    events.append(
        new dev.mikoto2000.rei.event.AgentEventFactory(Clock.systemUTC())
            .runStarted("past", "test", null)
            .withOwnership(new AgentRunContext("past", a, "chat:main")));
    assertThat(display.rows()).isEmpty();
    router.submit(a.root(), a.conversationId("chat:main"), "A");
    router.submit(b.root(), b.conversationId("chat:main"), "B");
    assertThat(display.rows())
        .anyMatch(row -> row.contains(a.id().substring(0, 8)))
        .anyMatch(row -> row.contains(b.id().substring(0, 8)));
  }
}
