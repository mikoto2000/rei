package dev.mikoto2000.rei.event;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.ui.shell.ShellAgentEventRenderer;
import dev.mikoto2000.rei.ui.shell.ShellEventOutput;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** プロジェクトエージェントイベント保存先の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ProjectAgentEventStoreTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path temp;

  /** 検証に使う生成元。 */
  AgentEventFactory factory = new AgentEventFactory(Clock.systemUTC());

  /** 検証用に、実行状況の通知の操作を実行する。 */
  AgentRunContext run(String name) {
    return new AgentRunContext(
        name, new ProjectContext(UUID.randomUUID().toString(), name, temp), "chat:main");
  }

  /** 実行状況の通知で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void projectsHaveIndependentDurableSequencesAndTypedPayloads() {
    var a = run("a");
    var b = run("b");
    var store = new ProjectAgentEventStore(temp);
    store.append(factory.runStarted("a", "test", null).withOwnership(a));
    store.append(factory.intervention("a", "input", "keep README", false).withOwnership(a));
    store.append(factory.runStarted("b", "test", null).withOwnership(b));
    var reopened = new ProjectAgentEventStore(temp);
    reopened.append(factory.intervention("a", "input", "keep README", true).withOwnership(a));
    assertThat(reopened.recent(a.projectId(), 2))
        .extracting(AgentEvent::sequence)
        .containsExactly(2L, 3L);
    assertThat(reopened.recent(b.projectId(), 10))
        .extracting(AgentEvent::sequence)
        .containsExactly(1L);
    assertThat(reopened.recent(a.projectId(), 1).getFirst().payload())
        .isInstanceOf(UserInterventionPayload.class);
    assertThat(reopened.lastSequence(a.projectId())).isEqualTo(3);
  }

  /** 同一表示への変換を利用することを確認する。 */
  @Test
  void persistedAndLiveEventsUseTheSameRenderer() {
    var a = run("a");
    var store = new ProjectAgentEventStore(temp);
    var event = factory.intervention("a", "input", "keep README", true).withOwnership(a);
    store.append(event);
    assertThat(render(store.recent(a.projectId(), 1).getFirst())).isEqualTo(render(event));
  }

  /** 実行状況の通知の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void interruptedTailDoesNotHideFutureEventsOrResetSequence() throws Exception {
    var a = run("a");
    var store = new ProjectAgentEventStore(temp);
    store.append(factory.runStarted("a", "test", null).withOwnership(a));
    Path file = temp.resolve("projects/" + a.projectId() + "/events/events.jsonl");
    Files.writeString(file, "{\"incomplete\":", StandardOpenOption.APPEND);
    var reopened = new ProjectAgentEventStore(temp);
    reopened.append(factory.intervention("a", "input", "日本語の追加入力", true).withOwnership(a));
    assertThat(reopened.recent(a.projectId(), 10))
        .extracting(AgentEvent::sequence)
        .containsExactly(1L, 2L);
    assertThat(reopened.readAfter(a.projectId(), 1, 10)).hasSize(1);
  }

  /** 実施できない書き込みを保存することを確認する。 */
  @Test
  void busContinuesWhenStoreCannotWrite() throws Exception {
    var blocked = Files.writeString(temp.resolve("blocked"), "file");
    var store = new ProjectAgentEventStore(blocked);
    var bus = new InMemoryAgentEventBus();
    bus.subscribe(store);
    List<AgentEvent> live = new ArrayList<>();
    bus.subscribe(live::add);
    var a = run("a");
    bus.publish(factory.runStarted("a", "test", null).withOwnership(a));
    assertThat(live).hasSize(1);
  }

  /** 検証用に、利用者が内容を確認できるよう、表示形式へ変換する。 */
  private String render(AgentEvent event) {
    var text = new StringBuilder();
    var renderer =
        new ShellAgentEventRenderer(
            new ShellEventOutput() {
              /** 検証で受け取った案内や結果を、表示の確認先へ出力する。 */
              public void print(String value) {
                text.append(value);
              }

              /** 検証で受け取った案内や結果を、表示の確認先へ出力する。 */
              public void println(String value) {
                text.append(value).append('\n');
              }

              /** 検証で保留している出力の確定を扱う。 */
              public void flush() {}
            });
    renderer.onEvent(event);
    return text.toString();
  }
}
