package dev.mikoto2000.rei.llm.capture;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import picocli.CommandLine;
class CaptureCommandTest {
  @TempDir Path temp;
  @Test void masksSeparateDisplayAndExportsExactBytesOnlyAfterConfirmation() throws Exception {
    var store=new CaptureStore();store.reserve("c","v");store.accept("c","v","s","r");
    byte[] original="{\"model\":\"test\",\"api_key\":\"sk-secret\",\"text\":\"Bearer secret-token\",\"password\":\"secret\"}".getBytes(StandardCharsets.UTF_8);
    var a=store.prepare(store.logicalCall("r",null),original,"application/json",null);
    var command=new LlmCaptureCommand(store,()->"c",()->"v",()->{});var cli=new CommandLine(command);var output=new StringWriter();cli.setOut(new PrintWriter(output,true));
    assertThat(cli.execute("capture","show",a.attemptId())).isZero();assertThat(output.toString()).contains("[REDACTED]").doesNotContain("sk-secret","secret-token","\"secret\"");
    assertThat(cli.execute("capture","export",a.attemptId(),temp.resolve("body.json").toString())).isEqualTo(2);assertThat(temp.resolve("body.json")).doesNotExist();
    command.setConfirmation(prompt->true);
    assertThat(cli.execute("capture","export",a.attemptId(),temp.resolve("body.json").toString())).isZero();assertThat(Files.readAllBytes(temp.resolve("body.json"))).isEqualTo(original);
    assertThat(cli.execute("capture","export",a.attemptId(),temp.resolve("body.json").toString())).isEqualTo(2);
    assertThat(store.body(a.attemptId())).isEqualTo(original);
  }
  @Test void rawEscapesTerminalControlsAndSkippedBodyCannotBeExported() {
    var store=new CaptureStore();store.reserve("c","v");store.accept("c","v","s","r");var logical=store.logicalCall("r",null);
    var a=store.prepare(logical,new byte[]{27,91,50,74},"application/json",null);
    var command=new LlmCaptureCommand(store,()->"c",()->"v",()->{});command.setConfirmation(prompt->true);
    var cli=new CommandLine(command);var output=new StringWriter();cli.setOut(new PrintWriter(output,true));
    assertThat(cli.execute("capture","raw",a.attemptId())).isZero();assertThat(output.toString()).contains("\\u001b").doesNotContain("\u001b");
    var skipped=store.prepare(logical,null,null,"SKIPPED_ONE_SHOT");
    assertThat(cli.execute("capture","export",skipped.attemptId(),temp.resolve("skip.json").toString())).isEqualTo(2);
  }
  @Test void managementCommandsDoNotConsumeReservationAndClearDeletesOriginal(){
    var store=new CaptureStore();var command=new LlmCaptureCommand(store,()->"c",()->"v",()->{});var cli=new CommandLine(command);
    cli.setOut(new PrintWriter(new StringWriter()));assertThat(cli.execute("capture","next")).isZero();
    assertThat(cli.execute("capture","status")).isZero();assertThat(cli.execute("capture","list")).isZero();
    assertThat(store.accept("c","v","s","r")).isTrue();assertThat(cli.execute("capture","delete","r")).isZero();
    assertThat(cli.execute("capture","clear")).isZero();assertThat(store.sessions()).isEmpty();
    assertThat(cli.execute("capture","unknown")).isEqualTo(2);
  }
}
