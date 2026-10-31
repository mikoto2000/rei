package dev.mikoto2000.rei.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchPreparationReuseTest {
  @TempDir Path root;

  private static class CountingTools extends Tools {
    int lists, compiles;
    List<String> inventory = List.of("one.md", "two.txt");

    @Override
    List<String> listFile(String base, Path directory) {
      lists++;
      return inventory;
    }

    @Override
    List<String> gitLsFiles(List<String> specs, Path directory) {
      lists++;
      return inventory;
    }

    @Override
    java.util.regex.Pattern compileGrepPattern(String p, boolean ignore, boolean fixed) {
      compiles++;
      return super.compileGrepPattern(p, ignore, fixed);
    }
  }

  private Tools.GrepQuery query(String glob) {
    return new Tools.GrepQuery(
        "needle", ".", false, true, false, false, 0, 0, null, true, glob, null);
  }

  @Test
  void differentGlobsShareOneBaseEnumerationWithoutMixingMatches() throws Exception {
    Files.writeString(root.resolve("one.md"), "needle md");
    Files.writeString(root.resolve("two.txt"), "needle txt");
    var tools = new CountingTools();
    var result = tools.grepMultiQuery(List.of(query("*.md"), query("*.txt")), root);
    assertThat(tools.lists).isEqualTo(1);
    assertThat(tools.compiles).isEqualTo(2);
    assertThat(result.get(0).matches()).extracting(Tools.GrepMatch::path).containsExactly("one.md");
    assertThat(result.get(1).matches())
        .extracting(Tools.GrepMatch::path)
        .containsExactly("two.txt");
  }

  @Test
  void narrowGlobStillWorksWhenUnfilteredBaseExceedsSelectedInventoryBudget() throws Exception {
    Files.writeString(root.resolve("one.md"), "needle");
    var tools = new CountingTools();
    var listed = new java.util.ArrayList<String>();
    listed.add("one.md");
    for (int i = 0; i < 1100; i++) listed.add("unselected-" + i + ".txt");
    tools.inventory = List.copyOf(listed);
    assertThat(tools.grepMultiQuery(List.of(query("*.md")), root).getFirst().matches()).hasSize(1);
  }

  @Test
  void pagedSearchPreparesFiltersOnceForAllFiles() throws Exception {
    Files.writeString(root.resolve("one.md"), "needle md");
    Files.writeString(root.resolve("two.txt"), "needle txt");
    var data = root.resolve("data");
    try (var migration = new StorageMigrationCoordinator(data)) {
      migration.prepare();
    }
    var tools = new CountingTools();
    try (var scope = AgentRunScope.open(new AgentRunContext("run", "chat", root, "project"))) {
      var search = new PagedFileSearch(tools, ReadingTestStores.store(new StorageDatabase(data)));
      var page =
          search.read(
              new PagedFileSearch.Request(
                  List.of(query("*.md"), query("*.txt")), 0, 2048, 16384, 128, 100, null),
              root);
      assertThat(page.error()).isNull();
      assertThat(page.progress().total()).isEqualTo(2);
      assertThat(tools.lists).isEqualTo(1);
      assertThat(tools.compiles).isEqualTo(2);
    }
  }
}
