package dev.mikoto2000.rei.sound;

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

class SoundNotificationServiceTest {

  private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
  private final PrintStream originalOut = System.out;

  @BeforeEach
  void setUpStreams() {
    System.setOut(new PrintStream(outContent));
  }

  @AfterEach
  void restoreStreams() {
    System.setOut(originalOut);
  }

  @Test
  void notifyFallsBackToConsoleWhenDisabled() {
    SoundNotificationProperties props = new SoundNotificationProperties();
    props.setEnabled(false);
    SoundNotificationService service = new SoundNotificationService(props);

    service.notify("disabled message");

    assertOutputContains("disabled message");
  }

  @Test
  void notifyFallsBackToConsoleWhenCommandIsEmpty() {
    SoundNotificationProperties props = new SoundNotificationProperties();
    props.setEnabled(true);
    SoundNotificationService service = new SoundNotificationService(props);

    service.notify("empty command message");

    assertOutputContains("empty command message");
  }

  @Test
  void notifyReplacesMessagePlaceholderInCommand() {
    SoundNotificationProperties props = new SoundNotificationProperties();
    props.setEnabled(true);
    props.setCommand(List.of("echo", "{{MESSAGE}}"));

    List<List<String>> capturedCommands = new ArrayList<>();
    SoundNotificationService service = new SoundNotificationService(props) {
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

  @Test
  void notifyFallsBackToConsoleOnTimeout() {
    SoundNotificationProperties props = new SoundNotificationProperties();
    props.setEnabled(true);
    props.setCommand(List.of("sleep", "{{MESSAGE}}"));

    SoundNotificationService service = new SoundNotificationService(props) {
      @Override
      protected ProcessBuilder createProcessBuilder(List<String> command) {
        return new ProcessBuilder("cmd", "/c", "ping", "-n", "100", "127.0.0.1");
      }

      @Override
      protected long getTimeoutSeconds() {
        return 1L;
      }
    };

    service.notify("timeout message");

    assertOutputContains("timeout message");
  }

  @Test
  void notifyFallsBackToConsoleWhenExternalProgramFails() {
    SoundNotificationProperties props = new SoundNotificationProperties();
    props.setEnabled(true);
    props.setCommand(List.of("echo", "{{MESSAGE}}"));

    SoundNotificationService service = new SoundNotificationService(props) {
      @Override
      protected ProcessBuilder createProcessBuilder(List<String> command) {
        return new ProcessBuilder("cmd", "/c", "exit", "1");
      }
    };

    service.notify("failure message");

    assertOutputContains("failure message");
  }

  @Test
  void notifyExecutesSeriallyWhenCalledConcurrently() throws Exception {
    SoundNotificationProperties props = new SoundNotificationProperties();
    props.setEnabled(true);
    props.setCommand(List.of("echo", "{{MESSAGE}}"));

    AtomicInteger inFlight = new AtomicInteger(0);
    AtomicInteger maxInFlight = new AtomicInteger(0);

    SoundNotificationService service = new SoundNotificationService(props) {
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

    pool.submit(() -> {
      await(start);
      service.notify("first");
      done.countDown();
    });
    pool.submit(() -> {
      await(start);
      service.notify("second");
      done.countDown();
    });

    start.countDown();
    assertTrue(done.await(5, TimeUnit.SECONDS));
    pool.shutdownNow();

    assertTrue(maxInFlight.get() <= 1, "notification executions should be serialized");
  }

  private void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  private void assertOutputContains(String expected) {
    assertTrue(outContent.toString().contains(expected), "console output should contain message: " + expected);
  }
}
