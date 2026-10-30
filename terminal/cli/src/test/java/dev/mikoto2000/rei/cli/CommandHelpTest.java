package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.mikoto2000.rei.application.cli.CliCatalog;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ヘルプが選択状態や Backend 接続に依存せず、コマンドを実行しないことを検証する。 */
class CommandHelpTest {
  @TempDir Path directory;

  @Test
  void everyClientLocalCommandAndSubcommandHasRoleAndUsageWithoutBackendAccess() throws Exception {
    var paths = new ArrayList<List<String>>();
    collect(ClientHelpCatalog.create(new CliCatalog(List.of())), List.of(), paths);
    assertTrue(paths.size() >= 30);
    var output = new ByteArrayOutputStream();
    try (var backend = new BackendClient(URI.create("http://127.0.0.1:1"), "fixture");
        var client =
            new TerminalClient(
                backend,
                directory,
                true,
                TerminalBuilder.builder()
                    .system(false)
                    .dumb(true)
                    .streams(new ByteArrayInputStream(new byte[0]), output)
                    .build())) {
      for (var path : paths) {
        if (path.isEmpty()) continue;
        output.reset();
        client.command("/" + String.join(" ", path) + " --help");
        var text = output.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.contains("Usage:"), path + "\n" + text);
        assertTrue(text.contains("--help"), path + "\n" + text);
      }
    }
  }

  @Test
  void helpWorksAtEveryDepthWithoutDeclaredHelpAndRespectsLiteralArguments() {
    var leaf =
        new CliCatalog.Command(
            "apply",
            List.of("run"),
            "変更を適用します。",
            List.of(),
            List.of(new CliCatalog.Parameter("0", "ID", "1", "対象 ID", List.of(), "")),
            List.of());
    var command =
        new CliCatalog.Command(
            "fixture", List.of(), "テスト用の操作です。", List.of(), List.of(), List.of(leaf));
    var cache = new CachedCatalog(new CliCatalog(List.of(command)));
    for (var words :
        List.of(
            List.of("/fixture", "--help"),
            List.of("/fixture", "apply", "--help"),
            List.of("/fixture", "run", "--help"),
            List.of("/fixture", "apply", "--help", "id"))) {
      assertTrue(cache.requestedHelp(words).contains("Usage:"), words.toString());
    }
    assertNull(cache.requestedHelp(List.of("/fixture", "apply", "--", "--help")));
    assertEquals(
        List.of("/chat", "--", "--help"), cache.normalize(List.of("/chat", "--", "--help")));
  }

  private static void collect(
      picocli.CommandLine line, List<String> path, List<List<String>> paths) {
    paths.add(path);
    assertTrue(line.getCommandSpec().userObject() == null, path.toString());
    assertTrue(line.getCommandSpec().usageMessage().description().length > 0, path.toString());
    for (var child : line.getSubcommands().entrySet()) {
      var nested = new ArrayList<>(path);
      nested.add(child.getKey());
      collect(child.getValue(), nested, paths);
    }
  }
}
