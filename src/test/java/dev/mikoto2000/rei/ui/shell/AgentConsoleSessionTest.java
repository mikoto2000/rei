package dev.mikoto2000.rei.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** エージェントコンソール会話の結果と状態の契約を検証するテスト。 */
class AgentConsoleSessionTest {
  /** ターミナルでの対話で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void rawAgentOutputDoesNotHideConcurrentShellOutput() throws Exception {
    var bytes = new ByteArrayOutputStream();
    var original = new PrintStream(bytes, true, StandardCharsets.UTF_8);
    var routed = AgentConsoleSession.route(original);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var run =
          executor.submit(
              () -> {
                try (var scope =
                    AgentRunScope.open(new AgentRunContext("run", "chat:main", Path.of(".")))) {
                  entered.countDown();
                  release.await();
                  routed.println("raw tool output");
                }
                return null;
              });
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      try {
        routed.println("ユーザー入力");
      } finally {
        release.countDown();
      }
      run.get(5, TimeUnit.SECONDS);
    }
    assertThat(bytes.toString(StandardCharsets.UTF_8)).isEqualTo("ユーザー入力" + System.lineSeparator());
  }
}
