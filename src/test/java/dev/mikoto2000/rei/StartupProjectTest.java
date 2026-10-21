package dev.mikoto2000.rei;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.mikoto2000.rei.core.command.ProjectCommand;
import dev.mikoto2000.rei.core.project.ProjectBeanScope;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.core.project.ProjectStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import picocli.CommandLine;

/** 起動時プロジェクトの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("e2e")
class StartupProjectTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path temp;

  /** れいの起動で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void absoluteAndShortOptionSelectTheSameProjectAsCd() throws Exception {
    var root = Files.createDirectory(temp.resolve("project with spaces"));
    var service = new ProjectService(temp, new ProjectRegistry(temp.resolve("projects.json")));
    ProjectContext expected;
    try (var scope = service.newClient().open()) {
      assertThat(new CommandLine(new ProjectCommand.CdCommand(service)).execute(root.toString()))
          .isZero();
      expected = service.currentContext();
    }
    for (String option : new String[] {"--project", "-p"}) {
      var app = mock(ReiApplication.class, CALLS_REAL_METHODS);
      ReflectionTestUtils.setField(app, "projects", service);
      doAnswer(
              invocation -> {
                assertThat(service.currentContext()).isEqualTo(expected);
                assertThat(ProjectService.contextForOperation()).isEqualTo(expected);
                assertThat(ProjectStorage.currentDirectory())
                    .isEqualTo(ProjectStorage.directory(expected.id()));
                assertThat(new ProjectBeanScope().getConversationId()).isEqualTo(expected.id());
                assertThat(dev.mikoto2000.rei.llm.ConversationIds.currentChat())
                    .isEqualTo(expected.conversationId("chat:main"));
                return null;
              })
          .when(app)
          .runShell(any(), any());
      app.run(new String[] {option, root.toString()});
      verify(app).runShell(any(), any());
      assertThat(ProjectService.contextForOperation()).isNull();
      try (var scope = service.newClient().open()) {
        assertThat(service.currentProject()).isEqualTo(temp);
      }
    }
  }

  /** 相対パス一覧について、起動時ディレクトリを利用することを確認する。 */
  @Test
  void relativePathsUseStartupDirectoryAndRegistryCanonicalization() throws Exception {
    var cwd = Files.createDirectory(temp.resolve("cwd"));
    var foo = Files.createDirectory(temp.resolve("foo"));
    assertThat(StartupOptions.parse(new String[] {"--project", "."}, cwd).project())
        .isEqualTo(cwd.toRealPath());
    for (String path : new String[] {"../foo", "../foo/../foo"}) {
      assertThat(StartupOptions.parse(new String[] {"--project", path}, cwd).project())
          .isEqualTo(foo.toRealPath());
    }
  }

  /** 存在しない選択項目について、起動時を保持することを確認する。 */
  @Test
  void absentOptionPreservesStartupAndDoesNotRegisterAnything() throws Exception {
    var file = temp.resolve("projects.json");
    var service = new ProjectService(temp, new ProjectRegistry(file));
    var app = mock(ReiApplication.class, CALLS_REAL_METHODS);
    ReflectionTestUtils.setField(app, "projects", service);
    doAnswer(
            invocation -> {
              assertThat(service.currentProject()).isEqualTo(temp);
              assertThat(file).doesNotExist();
              return null;
            })
        .when(app)
        .runShell(any(), any());
    app.run(new String[0]);
    verify(app).runShell(any(), any());
    assertThat(
            StartupOptions.parse(new String[] {"--rei.embedding.enabled=false", "--tui"}, temp)
                .project())
        .isNull();
  }

  /** れいの起動で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void invalidPathsDoNotStartShellOrChangeRegistryOrSelection() throws Exception {
    var file = temp.resolve("projects.json");
    var service = new ProjectService(temp, new ProjectRegistry(file));
    try (var scope = service.newClient().open()) {
      service.cd(temp.toString());
      var before = Files.readString(file);
      var regular = Files.writeString(temp.resolve("README.md"), "text");
      for (Path invalid : new Path[] {temp.resolve("missing"), regular}) {
        var app = mock(ReiApplication.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(app, "projects", service);
        assertThatThrownBy(() -> app.run(new String[] {"--project", invalid.toString()}))
            .isInstanceOf(CommandLine.ParameterException.class)
            .hasMessageContaining(invalid.toString())
            .hasMessageContaining("project");
        verify(app, never()).runShell(any(), any());
        assertThat(Files.readString(file)).isEqualTo(before);
        assertThat(service.currentProject()).isEqualTo(temp.toRealPath());
      }
      assertThat(temp.resolve("missing")).doesNotExist();
    }
  }

  /** れいの起動で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void missingValueIsParserErrorAndHelpDocumentsBothNames() {
    assertThatThrownBy(() -> StartupOptions.parse(new String[] {"--project"}, temp))
        .isInstanceOf(CommandLine.MissingParameterException.class);
    var help = StartupOptions.parse(new String[] {"--help"}, temp);
    assertThat(help.helpRequested()).isTrue();
    assertThat(new CommandLine(new StartupOptions()).getUsageMessage())
        .contains("-p", "--project", "<directory>");
  }

  /** 不正な入力を受け付けないことを確認する。 */
  @Test
  void mainRejectsInvalidInputBeforeSpringAndHelpExitsSuccessfully() throws Exception {
    var regular = Files.writeString(temp.resolve("file.txt"), "text");
    var missing = temp.resolve("missing");
    var data = temp.resolve("data");
    var classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    var arguments = temp.resolve("java.args");
    Files.writeString(
        arguments,
        "-Dstdout.encoding=UTF-8\n-Dstderr.encoding=UTF-8\n-cp\n\""
            + classpath.replace("\\", "/")
            + "\"\ndev.mikoto2000.rei.ReiApplication\n");
    for (String[] args :
        new String[][] {
          {"--project", missing.toString()}, {"-p", regular.toString()}, {"--project"}, {"--help"}
        }) {
      var command = new java.util.ArrayList<String>();
      command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
      command.add("@" + arguments);
      command.addAll(java.util.List.of(args));
      var output = temp.resolve("output.txt");
      var builder =
          new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
      builder.environment().put("REI_DATA_DIR", data.toString());
      var process = builder.start();
      try {
        assertThat(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        boolean help = args[0].equals("--help");
        assertThat(process.exitValue()).isEqualTo(help ? 0 : 2);
        var text = Files.readString(output);
        assertThat(text).doesNotContain("AI Shell", "Starting ReiApplication");
        if (help) assertThat(text).contains("--project", "-p", "<directory>");
        else if (args.length == 2) {
          assertThat(text)
              .contains(
                  args[1],
                  "project",
                  args[1].equals(missing.toString()) ? "does not exist" : "not a directory");
        } else assertThat(text).contains("--project");
        assertThat(data).doesNotExist();
        assertThat(missing).doesNotExist();
      } finally {
        if (process.isAlive()) process.destroyForcibly();
      }
    }
  }
}
