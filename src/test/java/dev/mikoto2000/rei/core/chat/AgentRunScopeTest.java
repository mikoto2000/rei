package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentRunScopeTest {
  @Test
  void bindingIsLexicalAndDoesNotLeakToShell() {
    var context = new AgentRunContext("run", "chat:main", Path.of("project-a"));
    assertThat(AgentRunScope.current()).isNull();
    try (var scope = AgentRunScope.open(context)) {
      assertThat(AgentRunScope.current()).isEqualTo(context);
    }
    assertThat(AgentRunScope.current()).isNull();
  }
}
