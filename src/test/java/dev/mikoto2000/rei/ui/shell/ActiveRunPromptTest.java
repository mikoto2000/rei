package dev.mikoto2000.rei.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import org.jline.reader.Widget;
import org.jline.reader.impl.BufferImpl;
import org.jline.reader.impl.LineReaderImpl;

/** 活動中実行プロンプトの結果と状態の契約を検証するテスト。 */
class ActiveRunPromptTest {
  @org.junit.jupiter.api.Test
  void progressUpdateRedrawsWithoutChangingInputAndUnsubscribes() throws Exception {
    var reader = mock(LineReaderImpl.class);
    var widgets = new HashMap<String, Widget>();
    when(reader.getWidgets()).thenReturn(widgets);
    when(reader.isReading()).thenReturn(true);
    doAnswer(
            call -> {
              widgets.get(call.getArgument(0)).apply();
              return null;
            })
        .when(reader)
        .callWidget(anyString());
    var store = new dev.mikoto2000.rei.context.TaskProgressStore();
    var display = mock(ActiveRunDisplay.class);
    when(display.onProgressChange(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(call -> store.onChange(call.getArgument(0)));
    var buffer = new BufferImpl();
    when(reader.readLine(anyString()))
        .thenAnswer(
            call -> {
              buffer.write("入力途中");
              buffer.cursor(2);
              store.update(
                  "run",
                  new dev.mikoto2000.rei.context.TaskStateSnapshot(
                      "目的", "IN_PROGRESS", java.util.List.of("調査"), java.util.List.of("実装")));
              assertThat(buffer.toString()).isEqualTo("入力途中");
              assertThat(buffer.cursor()).isEqualTo(2);
              verify(reader).setPrompt("updated progress");
              return buffer.toString();
            });
    try (var prompt = new ActiveRunPrompt(reader, null, () -> "updated progress", display)) {
      prompt.readLine();
    }
    store.remove("run");
    verify(reader, times(1)).redisplay();
  }

  /** ターミナルでの対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      dev.mikoto2000.rei.core.execution.ExecutionType.class)
  void startAndFinishRedrawWithoutChangingTypedInputOrCursor(
      dev.mikoto2000.rei.core.execution.ExecutionType type) {
    var reader = mock(LineReaderImpl.class);
    var widgets = new HashMap<String, Widget>();
    var buffer = new BufferImpl();
    when(reader.getWidgets()).thenReturn(widgets);
    when(reader.isReading()).thenReturn(true);
    when(reader.getBuffer()).thenReturn(buffer);
    doAnswer(
            call -> {
              widgets.get(call.getArgument(0)).apply();
              return null;
            })
        .when(reader)
        .callWidget(anyString());
    var tasks = new ArrayList<Runnable>();
    var router = new ConversationInputRouter(tasks::add, (c, p, q) -> {});
    when(reader.readLine(anyString()))
        .thenAnswer(
            call -> {
              buffer.write("入力途中");
              buffer.cursor(2);
              if (type == dev.mikoto2000.rei.core.execution.ExecutionType.AGENT) {
                router.submit(Path.of("a"), "chat:main", "work");
              } else {
                router.submitBackground(
                    new dev.mikoto2000.rei.core.project.ProjectContext(
                        UUID.randomUUID().toString(), "rei", Path.of("a")),
                    type,
                    "work",
                    e -> {});
              }
              verify(reader).setPrompt("[rei] [1 running]\n09:42 test-model> ");
              tasks.getFirst().run();
              verify(reader).setPrompt("[rei] [0 running]\n09:42 test-model> ");
              assertThat(buffer.toString()).isEqualTo("入力途中");
              assertThat(buffer.cursor()).isEqualTo(2);
              return buffer.toString();
            });
    try (var prompt =
        new ActiveRunPrompt(
            reader,
            router,
            () -> "[rei] [" + router.activeExecutions().size() + " running]\n09:42 test-model> ")) {
      assertThat(prompt.readLine()).isEqualTo("入力途中");
    }
    verify(reader, times(2)).redisplay();
  }
}
