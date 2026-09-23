package dev.mikoto2000.rei.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.*;
import java.util.*;
import org.jline.reader.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.core.command.*;
import dev.mikoto2000.rei.core.completion.*;
import dev.mikoto2000.rei.externalagent.ExternalAgentCommand;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

class ContextCompletionTest {
  @TempDir Path root;
  CommandLine command() {
    return new CommandLine(CommandSpec.create()).addSubcommand("project", new ProjectCommand())
        .addSubcommand("history", new HistoryCommand()).addSubcommand("agent", new ExternalAgentCommand())
        .addSubcommand("session", new SessionCommand())
        .addSubcommand("activity", new dev.mikoto2000.rei.activity.ActivityCommand());
  }
  List<Candidate> complete(CommandLine cmd, String line, int cursor) {
    var result = new ArrayList<Candidate>();
    new JLineCompletionAdapter(cmd, ReiLineReaderFactory.completionEngine(), () -> root)
        .complete(null, ReiLineReaderFactory.parser().parse(line, cursor, Parser.ParseContext.COMPLETE), result);
    return result;
  }
  List<String> values(CommandLine cmd, String line) {
    return complete(cmd, line, line.length()).stream().map(Candidate::value).toList();
  }
  @Test void rootCommandsAndPrefixComeFromLiveRegistry() {
    var cmd = command();
    assertThat(values(cmd, "/")).contains("/project", "/history", "/agent");
    assertThat(values(cmd, "/pro")).containsExactly("/project");
    var completer = ReiLineReaderFactory.completer(cmd);
    cmd.addSubcommand("plugin", new CommandLine(CommandSpec.create().name("plugin")));
    var candidates = new ArrayList<Candidate>();
    completer.complete(null, ReiLineReaderFactory.parser().parse("/plug", 5), candidates);
    assertThat(candidates).extracting(Candidate::value).containsExactly("/plugin");
  }
  @Test void subcommandsComeFromDefinitions() {
    assertThat(values(command(), "/history ")).contains("show", "search", "list");
    assertThat(values(command(), "/project ")).contains("add", "remove", "cd", "list");
  }
  @Test void activityActionsCompleteWithoutExecutingCaptureOrTimeline() {
    var cmd = command();
    assertThat(values(cmd, "/activity ")).containsExactlyInAnyOrder("today", "yesterday", "summary", "pause", "resume", "behavior", "classification");
    assertThat(values(cmd, "/activity y")).containsExactly("yesterday");
    assertThat(values(cmd, "/activity re")).containsExactly("resume");
    assertThat(values(cmd, "/activity today ")).isEmpty();
    assertThat(values(cmd, "/activity 2026-09-")).isEmpty();
  }
  @Test void behaviorActionsCompleteFromSubcommandDefinition() {
    assertThat(values(command(), "/activity behavior ")).containsExactlyInAnyOrder("on","off","status","evaluate","uncertain","suggest-rules");
    assertThat(values(command(), "/activity behavior ev")).containsExactly("evaluate");
  }
  @Test void classificationActionsCompleteFromSubcommandDefinition() {
    assertThat(values(command(), "/activity classification ")).containsExactlyInAnyOrder("status","reload","unknowns","rules","suggest-rules");
    assertThat(values(command(), "/activity classification su")).containsExactly("suggest-rules");
  }
  @Test void projectCdAndAddOnlyOfferDirectories() throws Exception {
    Files.createDirectory(root.resolve("docs")); Files.createFile(root.resolve("data.txt"));
    assertThat(values(command(), "/project cd ./d")).containsExactly("./docs/");
    assertThat(values(command(), "/project add ./d")).containsExactly("./docs/");
  }
  @Test void projectCdMergesRegisteredProjectsBeforeLocalDirectories() throws Exception {
    var projects = new dev.mikoto2000.rei.core.project.ProjectService(root, root.resolve("projects.json"));
    var registered = Files.createDirectory(root.resolve("registered"));
    Files.createFile(root.resolve("ordinary.txt"));
    projects.add(registered.toString());
    try (var scope = dev.mikoto2000.rei.core.project.ProjectClientScope.open(projects.newClient())) {
      var result = complete(command(), "/project cd ", 12);
      assertThat(result).extracting(Candidate::value).contains(registered.toRealPath().toString())
          .doesNotContain("ordinary.txt");
      assertThat(result.getFirst().group()).isEqualTo("project");
    }
  }
  @Test void externalAgentAndActionUseDomainDefinitions() {
    assertThat(values(command(), "/agent ")).containsExactly("codex");
    assertThat(values(command(), "/agent codex ")).containsExactly("review");
    assertThat(values(command(), "/agent unknown ")).isEmpty();
  }
  @Test void reviewCompletesFilesAndDirectories() throws Exception {
    Files.createFile(root.resolve("report.txt")); Files.createDirectory(root.resolve("reports"));
    assertThat(values(command(), "/agent codex review ./rep")).containsExactly("./report.txt", "./reports/");
  }
  @Test void cursorInMiddleUsesCurrentTokenNotLastToken() {
    assertThat(complete(command(), "/project c foo", 10)).extracting(Candidate::value).containsExactly("cd");
  }
  @Test void quotedPathsAndWindowsBackslashesStayIntact() throws Exception {
    Files.createDirectories(root.resolve("My Documents"));
    Files.createFile(root.resolve("My Documents/report.txt"));
    for (String quote : List.of("\"", "'")) {
      assertThat(values(command(), "/agent codex review " + quote + "./My Documents/rep"))
          .containsExactly("./My Documents/report.txt");
    }
    var line = "/agent codex review \"F:\\My Documents\\rep";
    var parsed = ReiLineReaderFactory.parser().parse(line, line.length(), Parser.ParseContext.COMPLETE);
    assertThat(parsed.word()).isEqualTo("F:\\My Documents\\rep");
    assertThat(parsed.words()).containsExactly(new UserInputParser().split(line));
  }
  @Test void jlineEscapingRoundTripsThroughExecutionParser() {
    for (String prefix : List.of("/project add ", "/project add \"", "/project add '")) {
      var parsed = (CompletingParsedLine) ReiLineReaderFactory.parser().parse(prefix, prefix.length(), Parser.ParseContext.COMPLETE);
      String path = "F:\\My Documents\\reports";
      String escaped = parsed.escape(path, true).toString();
      assertThat(new UserInputParser().split("/project add " + escaped)).endsWith(path);
    }
  }
  @Test void quoteCharactersInFilenamesAreNotLost() {
    for (String prefix : List.of("/project add ", "/project add \"", "/project add '")) {
      var parsed = (CompletingParsedLine) ReiLineReaderFactory.parser().parse(prefix, prefix.length(), Parser.ParseContext.COMPLETE);
      for (String path : List.of("./O'Reilly/", "./a\"b/", "./a'b\"c/", "./a b'c\"d/"))
        assertThat(new UserInputParser().split("/project add " + parsed.escape(path, true))).endsWith(path);
    }
  }
  @Test void quotesInsideWordsUseExecutionTokenization() {
    String text = "/project add ./a\" b\"c";
    assertThat(ReiLineReaderFactory.parser().parse(text, text.length(), Parser.ParseContext.COMPLETE).words())
        .containsExactly(new UserInputParser().split(text));
  }
  @Test void ordinaryChatRemainsQuietButExplicitPathsComplete() throws Exception {
    Files.createFile(root.resolve("report.txt"));
    assertThat(values(command(), "hello ")).isEmpty();
    assertThat(values(command(), "see ./rep")).containsExactly("./report.txt");
  }
  @Test void unknownTypedArgumentDoesNotFallBackToFiles() {
    assertThat(values(command(), "/agent ./")).isEmpty();
  }
  @Test void optionsDoNotShiftPositionalArgumentsAndFileOptionUsesMetadata() throws Exception {
    Files.createFile(root.resolve("report.txt"));
    var cmd = command().addSubcommand("image", new dev.mikoto2000.rei.image.command.ImageCommand());
    assertThat(values(cmd, "/image generate --output ./rep")).containsExactly("./report.txt");
    assertThat(values(cmd, "/history --limit 10 sh")).containsExactly("show");
    assertThat(values(cmd, "/history --li")).containsExactly("--limit");
  }
  @Test void metadataCanRegisterCustomProviderWithoutShellChanges() {
    var spec = CommandSpec.create().name("plugin").addPositional(picocli.CommandLine.Model.PositionalParamSpec.builder()
        .index("0").completionCandidates(new CompletionMetadata() {
          public Set<String> types(CompletionContext context) { return Set.of("tool"); }
        }).build());
    var cmd = command().addSubcommand("plugin", new CommandLine(spec));
    var engine = ReiLineReaderFactory.completionEngine().register(new CompletionProvider() {
      public boolean supports(CompletionContext context) { return context.types().contains("tool"); }
      public List<CompletionCandidate> complete(CompletionContext context) { return List.of(CompletionCandidate.value("my-tool", "tool")); }
    });
    var results = new ArrayList<Candidate>();
    new JLineCompletionAdapter(cmd, engine, () -> root)
        .complete(null, ReiLineReaderFactory.parser().parse("/plugin ", 8), results);
    assertThat(results).extracting(Candidate::value).containsExactly("my-tool");
  }
}
