package dev.mikoto2000.rei.ui.shell;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.jline.reader.*;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.reader.impl.BufferImpl;
import dev.mikoto2000.rei.core.chat.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ActiveRunPromptTest {
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(dev.mikoto2000.rei.core.execution.ExecutionType.class)
  void startAndFinishRedrawWithoutChangingTypedInputOrCursor(dev.mikoto2000.rei.core.execution.ExecutionType type) {
    var reader = mock(LineReaderImpl.class);
    var widgets = new HashMap<String, Widget>();
    var buffer = new BufferImpl();
    when(reader.getWidgets()).thenReturn(widgets);
    when(reader.isReading()).thenReturn(true);
    when(reader.getBuffer()).thenReturn(buffer);
    doAnswer(call -> { widgets.get(call.getArgument(0)).apply(); return null; }).when(reader).callWidget(anyString());
    var tasks = new ArrayList<Runnable>();
    var router = new ConversationInputRouter(tasks::add, (c,p,q) -> {});
    when(reader.readLine(anyString())).thenAnswer(call -> {
      buffer.write("入力途中"); buffer.cursor(2);
      if (type == dev.mikoto2000.rei.core.execution.ExecutionType.AGENT) {
        router.submit(Path.of("a"), "chat:main", "work");
      } else {
        router.submitBackground(new dev.mikoto2000.rei.core.project.ProjectContext(
            UUID.randomUUID().toString(), "rei", Path.of("a")), type, "work", e -> {});
      }
      verify(reader).setPrompt("[rei] [1 running]\n09:42 test-model> ");
      tasks.getFirst().run();
      verify(reader).setPrompt("[rei] [0 running]\n09:42 test-model> ");
      assertThat(buffer.toString()).isEqualTo("入力途中");
      assertThat(buffer.cursor()).isEqualTo(2);
      return buffer.toString();
    });
    try (var prompt = new ActiveRunPrompt(reader, router, () -> "[rei] [" + router.activeExecutions().size() + " running]\n09:42 test-model> ")) {
      assertThat(prompt.readLine()).isEqualTo("入力途中");
    }
    verify(reader, times(2)).redisplay();
  }
}
