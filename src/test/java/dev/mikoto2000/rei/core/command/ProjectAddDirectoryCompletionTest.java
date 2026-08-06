package dev.mikoto2000.rei.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectAddDirectoryCompletionTest {

  @TempDir
  Path tempDir;

  @Test
  void completesAbsoluteDirectoryFragment() throws Exception {
    Path target = tempDir.resolve("project-alpha");
    java.nio.file.Files.createDirectory(target);
    java.nio.file.Files.createDirectory(tempDir.resolve("other"));

    String fragment = tempDir.resolve("proj").toString();

    assertThat(ProjectAddDirectoryCompletion.complete("/project add " + fragment, tempDir))
        .containsExactly(target.toString());
  }

  @Test
  void completesRelativeDirectoryFragmentFromCurrentProject() throws Exception {
    Path target = tempDir.resolve("src-main");
    java.nio.file.Files.createDirectory(target);
    java.nio.file.Files.createDirectory(tempDir.resolve("build"));

    assertThat(ProjectAddDirectoryCompletion.complete("/project add src", tempDir))
        .containsExactly(target.toString());
  }

  @Test
  void quotesCandidateWhenInputIsQuoted() throws Exception {
    Path target = tempDir.resolve("project space");
    java.nio.file.Files.createDirectory(target);

    String fragment = tempDir.resolve("project").toString();

    assertThat(ProjectAddDirectoryCompletion.complete("/project add \"" + fragment, tempDir))
        .containsExactly("\"" + target + "\"");
  }
}
