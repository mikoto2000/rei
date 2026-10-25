package dev.mikoto2000.rei.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class CancelCommandStateTest {
  @Test
  void explicitRunDistinguishesNewRequestWaitingFinishedAndMissing() {
    var cancellation = new CommandCancellationService();
    cancellation.prepare(new AgentRunContext("one", "session", Path.of("."), "project"));
    var output = new StringWriter();
    var command = new CommandLine(new CancelCommand(cancellation));
    command.setOut(new PrintWriter(output));
    assertThat(command.execute("one")).isZero();
    assertThat(output.toString()).contains("停止を要求しました", "one");
    output.getBuffer().setLength(0);
    assertThat(command.execute("one")).isZero();
    assertThat(output.toString()).contains("既に停止要求済み", "終了を待っています");
    cancellation.finish("one");
    output.getBuffer().setLength(0);
    assertThat(command.execute("one")).isZero();
    assertThat(output.toString()).contains("既に終了");
    output.getBuffer().setLength(0);
    assertThat(command.execute("missing")).isZero();
    assertThat(output.toString()).contains("存在しません");
  }
}
