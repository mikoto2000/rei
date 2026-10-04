package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.*;

@org.junit.jupiter.api.Tag("integration")
class ToolResultCompressorTest {
  @TempDir Path dir;
  final TokenEstimator tokens = TokenEstimator.conservative();
  Message result(String text) { return ToolResponseMessage.builder().responses(List.of(
      new ToolResponseMessage.ToolResponse("call", "runCommand", text))).build(); }
  @Test void smallResultsStayUnchanged() {
    var message = result("exitCode: 0");
    assertThat(new ToolResultCompressor(new RawToolResultStore(dir), tokens, 100, 70)
        .compact(message, "chat", "run")).isSameAs(message);
  }
  @Test void commandJsonKeepsStructuredDiagnosisBeforeVerboseProgressLogs() {
    var command=new dev.mikoto2000.rei.core.process.RunCommandResult("failed","foreground","foreground",1,
        "progress\\n".repeat(3000),"[ERROR] demo.AppTest.fail -- Time elapsed: 0.1 s <<< FAILURE!",null,null,false,null);
    String raw=new org.springframework.ai.tool.execution.DefaultToolCallResultConverter().convert(command,null);
    var compact=(ToolResponseMessage)new ToolResultCompressor(new RawToolResultStore(dir),tokens,100,600).compact(result(raw),"chat","run");
    assertThat(compact.getResponses().getFirst().responseData()).contains("TEST_FAILURE","demo.AppTest.fail");
  }
  @Test void largeResultsKeepDiagnosticsAndRetrievableRawBody() {
    String raw = "command: ./mvnw test\nstatus: FAILED\nexitCode: 1\n" + "progress\n".repeat(500)
        + "NativeSessionClientTest failed\nexpected 42 but was 41\nBUILD FAILURE\n";
    var store = new RawToolResultStore(dir);
    var original = result(raw);
    var compact = (ToolResponseMessage) new ToolResultCompressor(store, tokens, 100, 300)
        .compact(original, "chat", "run");
    String text = compact.getResponses().getFirst().responseData();
    assertThat(text).contains("exitCode: 1", "status: FAILED", "expected 42", "rawResultRef:");
    String ref = text.lines().filter(s -> s.startsWith("rawResultRef: ")).findFirst().orElseThrow().substring(14);
    assertThat(new RawToolResultStore(dir).read("chat", ref).rawResult()).isEqualTo(raw);
    assertThat(tokens.message(compact)).isLessThan(tokens.message(original));
    assertThatThrownBy(() -> store.read("another conversation", ref)).isInstanceOf(IllegalStateException.class);
  }
}
