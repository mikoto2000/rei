package dev.mikoto2000.rei.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 移行が長い場合も、Shell 起動前の待ち状態を利用者へ通知する。 */
class StorageMigrationProgressTest {
  @Test
  void reportsWhileWorkIsBlockedAndStopsWhenClosed() throws Exception {
    var heartbeat = new CountDownLatch(1);
    var bytes = new ByteArrayOutputStream();
    var output =
        new PrintStream(bytes, true, StandardCharsets.UTF_8) {
          @Override
          public PrintStream printf(String format, Object... args) {
            super.printf(format, args);
            if (Thread.currentThread().getName().equals("storage-migration-progress")) {
              heartbeat.countDown();
            }
            return this;
          }
        };
    var progress = new StorageMigrationProgress(output, 10, 18, Duration.ofMillis(20));
    try (progress) {
      progress.step("バックアップを作成中");
      assertThat(heartbeat.await(5, TimeUnit.SECONDS)).isTrue();
    }
    int closedSize = bytes.size();
    progress.step("表示されない状態");
    assertThat(bytes.size()).isEqualTo(closedSize);
    String text = bytes.toString(StandardCharsets.UTF_8);
    assertThat(text).contains("10 → 18", "Shell は完了後に開きます", "バックアップを作成中", "経過");
  }
}
