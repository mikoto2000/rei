package dev.mikoto2000.rei.durable;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

class WorkItemCrashTest {
  @TempDir Path temporary;

  @ParameterizedTest
  @ValueSource(strings = {"before", "after"})
  void processDeathRecoversEitherUncommittedWorkOrExactlyOneCommittedResult(String boundary) throws Exception {
    var fixture = new TaskSnapshotTest();
    fixture.temporary = temporary;
    var store = fixture.store();
    var root = Files.createDirectory(temporary.resolve("repository"));
    Files.writeString(root.resolve("a.java"), "a");
    var scope = new DurableTaskStore.Scope("project", "session", root.toString());
    var task = store.beginForRun(scope, "run", null, "WORKTREE", "", List.of(), 100, 1024);
    new SnapshotEnumerator(store).step(scope, task.id(), 100);
    var work = new WorkItemStore(store);
    var claim = work.claim(scope, task.id(), "run");
    work.delivered(scope, claim, WorkItemStore.hash("a".getBytes(StandardCharsets.UTF_8)));
    String executable = Path.of(System.getProperty("java.home"), "bin",
        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
    String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    var arguments = temporary.resolve("worker.args");
    Files.writeString(arguments, "-cp\n" + quote(classpath) + "\n" + WorkItemCrashWorker.class.getName()
        + "\n" + quote(temporary.toString()) + "\n" + task.id() + "\n" + claim.item().id()
        + "\n" + claim.attempt() + "\n" + boundary);
    var log = temporary.resolve("worker.log");
    var child = new ProcessBuilder(executable, "@" + arguments).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try {
      assertThat(child.waitFor(45, TimeUnit.SECONDS)).isTrue();
      assertThat(child.exitValue()).withFailMessage(Files.readString(log)).isEqualTo(boundary.equals("before") ? 71 : 72);
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        child.waitFor(10, TimeUnit.SECONDS);
      }
    }
    var restarted = new WorkItemStore(fixture.store());
    restarted.resume(scope, task.id(), "restarted");
    if (boundary.equals("before")) {
      assertThat(restarted.results(scope, task.id(), null, 10).entries()).isEmpty();
      var recovered = restarted.claim(scope, task.id(), "restarted");
      assertThat(recovered.item().id()).isEqualTo(claim.item().id());
      restarted.delivered(scope, recovered, WorkItemStore.hash("a".getBytes(StandardCharsets.UTF_8)));
      restarted.commit(scope, recovered, "checked", List.of());
    }
    assertThat(restarted.results(scope, task.id(), null, 10).entries()).hasSize(1);
    assertThat(store.verify(scope, task.id()).complete()).isTrue();
  }

  private static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
