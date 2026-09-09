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
  @Test void startAndFinishRedrawWithoutChangingTypedInputOrCursor() {
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
      router.submit(Path.of("a"), "chat:main", "work");
      verify(reader).setPrompt("[1 running] > ");
      tasks.getFirst().run();
      verify(reader).setPrompt("[0 running] > ");
      assertThat(buffer.toString()).isEqualTo("入力途中");
      assertThat(buffer.cursor()).isEqualTo(2);
      return buffer.toString();
    });
    try (var prompt = new ActiveRunPrompt(reader, router, () -> "[" + router.activeRuns().size() + " running] > ")) {
      assertThat(prompt.readLine()).isEqualTo("入力途中");
    }
    verify(reader, times(2)).redisplay();
  }
}
