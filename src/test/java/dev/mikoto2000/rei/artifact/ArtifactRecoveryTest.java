package dev.mikoto2000.rei.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** 成果物復旧の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class ArtifactRecoveryTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 成果物の管理で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void hardKillNeverPublishesAnUnconfirmedReceiptOrRetriesItsSource() throws Exception {
    var projects = new ProjectRegistry(root.resolve("projects.json"));
    var project = projects.resolve(root);
    for (String window : new String[] {"RESERVED", "FILE_MOVED"}) {
      var directory = Files.createDirectory(root.resolve(window));
      var process =
          new ProcessBuilder(
                  dev.mikoto2000.rei.testsupport.JavaFixtureCommand.command(
                      directory, Probe.class, root.toString(), directory.toString(), window))
              .redirectErrorStream(true)
              .redirectOutput(directory.resolve("owner.log").toFile())
              .start();
      try {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(directory.resolve("ready")) && System.nanoTime() < deadline)
          Thread.sleep(10);
        assertThat(Files.exists(directory.resolve("ready"))).isTrue();
        String id = Files.readString(directory.resolve("ready"));
        var source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + directory.resolve("state.db"));
        var observer =
            new ArtifactStore(
                source,
                projects,
                directory.resolve("delivery"),
                Clock.systemUTC(),
                new ArtifactProperties());
        assertThat(observer.get(project.id(), "session", id).status()).isEqualTo("PUBLISHING");
        assertThatThrownBy(() -> observer.content(project.id(), "session", id))
            .isInstanceOf(ArtifactException.class);
        process.destroyForcibly();
        assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
        assertThat(observer.get(project.id(), "session", id).status()).isEqualTo("UNKNOWN");
        assertThatThrownBy(
                () ->
                    observer.publish(
                        new AgentRunContext("run", "session", root, project.id()),
                        "generated",
                        "text/plain",
                        "result.txt",
                        "safe".getBytes()))
            .isInstanceOf(ArtifactException.class);
        assertThat(observer.list(project.id(), "session", null, 100, null).items()).hasSize(1);
      } finally {
        if (process.isAlive()) process.destroyForcibly();
        assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  /** 検証条件を再現するための利用可否の確認。 */
  public static class Probe {
    /** 検証用に、成果物の管理の起動要求を受け付け、実行を開始する。 */
    public static void main(String[] args) throws Exception {
      Path root = Path.of(args[0]), directory = Path.of(args[1]);
      var projects = new ProjectRegistry(root.resolve("projects.json"));
      var project = projects.resolve(root);
      var source = new SQLiteDataSource();
      source.setUrl("jdbc:sqlite:" + directory.resolve("state.db"));
      var store =
          new ArtifactStore(
              source,
              projects,
              directory.resolve("delivery"),
              Clock.systemUTC(),
              new ArtifactProperties(),
              (stage, id) -> {
                if (stage.name().equals(args[2]))
                  try {
                    Path marker = directory.resolve("ready.tmp");
                    Files.writeString(marker, id);
                    Files.move(marker, directory.resolve("ready"), StandardCopyOption.ATOMIC_MOVE);
                    Thread.sleep(60000);
                  } catch (Exception error) {
                    throw new IllegalStateException(error);
                  }
              });
      store.publish(
          new AgentRunContext("run", "session", root, project.id()),
          "generated",
          "text/plain",
          "result.txt",
          "safe".getBytes());
    }
  }
}
