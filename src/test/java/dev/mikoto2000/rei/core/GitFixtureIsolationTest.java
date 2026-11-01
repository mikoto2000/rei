package dev.mikoto2000.rei.core;

import static dev.mikoto2000.rei.core.SelfPatchReviewGitTest.copyRepository;
import static dev.mikoto2000.rei.core.SelfPatchReviewGitTest.initializeRepository;
import static dev.mikoto2000.rei.core.SelfPatchReviewGitTest.git;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Native Git fixture copies preserve repository semantics without sharing mutable state. */
@Tag("integration")
class GitFixtureIsolationTest {
  /** Contains a seed and two independently mutated copies. */
  @TempDir Path root;
  /** Copied repositories share no mutable history, index, configuration or worktree files. */
  @Test
  void copiedRepositoriesKeepHistoryConfigurationIndexAndWorktreeIndependent() throws Exception {
    Path source = Files.createDirectory(root.resolve("source"));
    initializeRepository(source);
    Path first = Files.createDirectory(root.resolve("first"));
    Path second = Files.createDirectory(root.resolve("second"));
    copyRepository(source, first);
    copyRepository(source, second);
    assertTrue(Files.isRegularFile(first.resolve(".git/index")), "First copy has a real index");
    assertTrue(Files.isRegularFile(second.resolve(".git/index")), "Second copy has a real index");
    byte[] sourceIndex = Files.readAllBytes(source.resolve(".git/index"));
    byte[] sourceConfig = Files.readAllBytes(source.resolve(".git/config"));
    byte[] sourceHead = Files.readAllBytes(source.resolve(".git/refs/heads/" + branch(source)));
    assertArrayEquals(sourceIndex, Files.readAllBytes(first.resolve(".git/index")));
    assertArrayEquals(sourceIndex, Files.readAllBytes(second.resolve(".git/index")));
    git(first, "config", "fixture.changed", "first");
    Files.writeString(first.resolve("A.txt"), "first change\n");
    git(first, "add", "--", "A.txt");
    git(first, "commit", "--quiet", "--no-gpg-sign", "-m", "first only");
    assertEquals("before\n", Files.readString(second.resolve("A.txt")));
    assertEquals("before\n", Files.readString(source.resolve("A.txt")));
    assertArrayEquals(sourceIndex, Files.readAllBytes(source.resolve(".git/index")));
    assertArrayEquals(sourceIndex, Files.readAllBytes(second.resolve(".git/index")));
    assertArrayEquals(sourceConfig, Files.readAllBytes(source.resolve(".git/config")));
    assertArrayEquals(sourceConfig, Files.readAllBytes(second.resolve(".git/config")));
    assertArrayEquals(sourceHead,
        Files.readAllBytes(second.resolve(".git/refs/heads/" + branch(second))));
    assertArrayEquals(sourceHead,
        Files.readAllBytes(source.resolve(".git/refs/heads/" + branch(source))));
    assertNotEquals(Files.readString(first.resolve(".git/refs/heads/" + branch(first))),
        Files.readString(second.resolve(".git/refs/heads/" + branch(second))));
  }

  /** Returns the controlled seed's local branch without consulting external configuration. */
  private static String branch(Path repository) throws Exception {
    return Files.readString(repository.resolve(".git/HEAD")).trim().substring("ref: refs/heads/".length());
  }

}
