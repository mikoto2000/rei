package dev.mikoto2000.rei.ui.shell.sound;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 音通知通知サービスの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("e2e")
class SoundNotificationServiceTest {

  /** 検証に使う出力先内容。 */
  private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();

  /** 検証に使う元の出力先。 */
  private final PrintStream originalOut = System.out;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setUpStreams() {
    System.setOut(new PrintStream(outContent));
  }

  /** 次の検証へ状態を持ち越さないよう、利用した資源と保存先を片付ける。 */
  @AfterEach
  void restoreStreams() {
    System.setOut(originalOut);
  }

  /** ターミナルの音通知で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void notifyFallsBackToConsoleWhenDisabled() {
    SoundNotificationProperties props = new SoundNotificationProperties();
    props.setEnabled(false);
    SoundNotificationService service = new SoundNotificationService(props);

    service.notify("disabled message");

    assertOutputContains("disabled message");
  }

  /** ターミナルの音通知で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void notifyFallsBackToConsoleWhenCommandIsEmpty() {
    SoundNotificationProperties props = new SoundNotificationProperties();
    props.setEnabled(true);
    SoundNotificationService service = new SoundNotificationService(props);

    service.notify("empty command message");

    assertOutputContains("empty command message");
  }

  /** ターミナルの音通知で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void notifyReplacesMessagePlaceholderInCommand() {
    SoundNotificationProperties props = new SoundNotificationProperties();
    props.setEnabled(true);
    props.setCommand(List.of("echo", "{{MESSAGE}}"));

    List<List<String>> capturedCommands = new ArrayList<>();
    SoundNotificationService service =
        new SoundNotificationService(props) {
          /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
          @Override
          protected ProcessBuilder createProcessBuilder(List<String> command) {
            capturedCommands.add(new ArrayList<>(command));
            return new ProcessBuilder("cmd", "/c", "exit", "0");
          }
        };

    service.notify("hello world");

    assertFalse(capturedCommands.isEmpty());
    assertTrue(capturedCommands.getFirst().contains("hello world"));
    assertFalse(capturedCommands.getFirst().contains("{{MESSAGE}}"));
  }

  /** ターミナルの音通知で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
  @Test
  void notifyFallsBackToConsoleOnTimeout() {
    SoundNotificationProperties props = new SoundNotificationProperties();
    props.setEnabled(true);
    props.setCommand(List.of("sleep", "{{MESSAGE}}"));

    SoundNotificationService service =
        new SoundNotificationService(props) {
          /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
          @Override
          protected ProcessBuilder createProcessBuilder(List<String> command) {
            return new ProcessBuilder("cmd", "/c", "ping", "-n", "100", "127.0.0.1");
          }

          /** 検証用に、ターミナルの音通知で参照するタイムアウトの秒数を返す。 */
          @Override
          protected long getTimeoutSeconds() {
            return 1L;
          }
        };

    service.notify("timeout message");

    assertOutputContains("timeout message");
  }

  /** ターミナルの音通知で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void notifyFallsBackToConsoleWhenExternalProgramFails() {
    SoundNotificationProperties props = new SoundNotificationProperties();
    props.setEnabled(true);
    props.setCommand(List.of("echo", "{{MESSAGE}}"));

    SoundNotificationService service =
        new SoundNotificationService(props) {
          /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
          @Override
          protected ProcessBuilder createProcessBuilder(List<String> command) {
            return new ProcessBuilder("cmd", "/c", "exit", "1");
          }
        };

    service.notify("failure message");

    assertOutputContains("failure message");
  }

  /** ターミナルの音通知で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void notifyExecutesSeriallyWhenCalledConcurrently() throws Exception {
    SoundNotificationProperties props = new SoundNotificationProperties();
    props.setEnabled(true);
    props.setCommand(List.of("echo", "{{MESSAGE}}"));

    AtomicInteger inFlight = new AtomicInteger(0);
    AtomicInteger maxInFlight = new AtomicInteger(0);

    SoundNotificationService service =
        new SoundNotificationService(props) {
          /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
          @Override
          protected ProcessBuilder createProcessBuilder(List<String> command) {
            int current = inFlight.incrementAndGet();
            maxInFlight.updateAndGet(prev -> Math.max(prev, current));
            try {
              Thread.sleep(100);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              inFlight.decrementAndGet();
            }
            return new ProcessBuilder("cmd", "/c", "exit", "0");
          }
        };

    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(2);

    pool.submit(
        () -> {
          await(start);
          service.notify("first");
          done.countDown();
        });
    pool.submit(
        () -> {
          await(start);
          service.notify("second");
          done.countDown();
        });

    start.countDown();
    assertTrue(done.await(5, TimeUnit.SECONDS));
    pool.shutdownNow();

    assertTrue(maxInFlight.get() <= 1, "notification executions should be serialized");
  }

  /** 検証用に、必要な状態が整ってから先へ進むため、音通知通知サービス検証を待つ。 */
  private void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  /** 複数の検証で共通する期待結果を確認し、要件に合わない状態を検出する。 */
  private void assertOutputContains(String expected) {
    assertTrue(
        outContent.toString().contains(expected),
        "console output should contain message: " + expected);
  }
}
