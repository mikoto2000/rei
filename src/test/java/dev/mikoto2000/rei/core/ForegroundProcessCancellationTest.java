package dev.mikoto2000.rei.core;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.io.InputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ForegroundProcessCancellationTest {
  @Test
  void interruptionTerminatesOnlyOwnedProcessTreeAndRestoresInterrupt() throws Exception {
    var process = mock(Process.class);
    var root = mock(ProcessHandle.class);
    var child = mock(ProcessHandle.class);
    var unrelated = mock(ProcessHandle.class);
    when(process.getInputStream()).thenReturn(InputStream.nullInputStream());
    when(process.getErrorStream()).thenReturn(InputStream.nullInputStream());
    when(process.toHandle()).thenReturn(root);
    when(root.descendants()).thenAnswer(i -> Stream.of(child));
    when(root.isAlive()).thenReturn(true, false);
    when(child.isAlive()).thenReturn(true, false);
    when(process.waitFor(anyLong(), any())).thenThrow(new InterruptedException("cancelled"));
    var tools =
        new Tools() {
          @Override
          Process startProcess(ProcessBuilder builder) {
            return process;
          }
        };
    try {
      assertThatThrownBy(() -> tools.executeShellCommand("fake", 5))
          .isInstanceOf(InterruptedException.class);
      verify(root).destroy();
      verify(child).destroy();
      verifyNoInteractions(unrelated);
      org.assertj.core.api.Assertions.assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }
}
