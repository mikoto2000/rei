package dev.mikoto2000.rei.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.ProfileEventLogStore;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 利用者情報コマンドの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ProfileCommandTest {

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path tempDir;

  /** 共通の実行基盤で、記録した情報を、定められた分類と集約の規則に従ってまとめることを確認する。 */
  @Test
  void summaryPrintsCountsAndDurations() {
    ProfileEventLogStore store = store();
    AgentEventFactory events =
        new AgentEventFactory(
            Clock.fixed(Instant.parse("2026-08-16T16:30:20Z"), ZoneId.of("Asia/Tokyo")));
    store.append(events.runStarted("run-1", "chat", null));
    store.append(events.runCompleted("run-1", 250L));

    String output = capture(() -> new ProfileCommand(store).printSummary());

    assertThat(output).contains("events: 2");
    assertThat(output).contains("agent.run.started 1");
    assertThat(output).contains("agent.run.completed 1");
    assertThat(output).contains("avg=250");
  }

  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void chartPrintsBuckets() {
    ProfileEventLogStore store = store();
    AgentEventFactory events =
        new AgentEventFactory(
            Clock.fixed(Instant.parse("2026-08-16T16:30:20Z"), ZoneId.of("Asia/Tokyo")));
    store.append(events.messageStarted("message-1", "assistant"));

    String output = capture(() -> new ProfileCommand(store).printChart(60L, 10));

    assertThat(output).contains("|");
    assertThat(output).contains("#");
    assertThat(output).contains("1");
  }

  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void mermaidPrintsXyChart() {
    ProfileEventLogStore store = store();
    AgentEventFactory events =
        new AgentEventFactory(
            Clock.fixed(Instant.parse("2026-08-16T16:30:20Z"), ZoneId.of("Asia/Tokyo")));
    store.append(events.messageStarted("message-1", "assistant"));

    String output = capture(() -> new ProfileCommand(store).printMermaid(10));

    assertThat(output).contains("```mermaid");
    assertThat(output).contains("xychart-beta");
    assertThat(output).contains("message.started");
  }

  /** 検証で使用する保存先を、指定された条件に合わせて用意する。 */
  private ProfileEventLogStore store() {
    return new ProfileEventLogStore(
        tempDir.resolve(".rei").resolve("profile.log"),
        new ObjectMapper().registerModule(new JavaTimeModule()));
  }

  /** {@code capture} の条件を再現するための補助情報を返す。 */
  private String capture(Runnable runnable) {
    PrintStream previous = System.out;
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try {
      System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
      runnable.run();
      return output.toString(StandardCharsets.UTF_8);
    } finally {
      System.setOut(previous);
    }
  }
}
