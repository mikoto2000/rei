package dev.mikoto2000.rei.ui.shell;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.core.chat.*;
import static org.assertj.core.api.Assertions.*;

class AgentConsoleSessionTest {
  @Test void rawAgentOutputDoesNotHideConcurrentShellOutput() throws Exception {
    var bytes = new ByteArrayOutputStream();
    var original = new PrintStream(bytes, true, StandardCharsets.UTF_8);
    var routed = AgentConsoleSession.route(original);
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var run = executor.submit(() -> {
        try (var scope = AgentRunScope.open(new AgentRunContext("run", "chat:main", Path.of(".")))) {
          entered.countDown(); release.await(); routed.println("raw tool output");
        }
        return null;
      });
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      try { routed.println("ユーザー入力"); } finally { release.countDown(); }
      run.get(5, TimeUnit.SECONDS);
    }
    assertThat(bytes.toString(StandardCharsets.UTF_8)).isEqualTo("ユーザー入力" + System.lineSeparator());
  }
}
