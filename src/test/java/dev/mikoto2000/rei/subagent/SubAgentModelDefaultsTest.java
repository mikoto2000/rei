package dev.mikoto2000.rei.subagent;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import dev.mikoto2000.rei.core.chat.ToolLoopSupport;

class SubAgentModelDefaultsTest {
  @Test void ambientToolsAreRejectedBeforeProviderOptionMerging() {
    ChatModel unsafe = new ChatModel() {
      public ChatResponse call(Prompt prompt) { throw new AssertionError(); }
      public ChatOptions getDefaultOptions() { return ToolCallingChatOptions.builder().toolNames("runCommand").build(); }
    };
    assertThatThrownBy(() -> ToolLoopSupport.requireNoDefaultTools(unsafe)).isInstanceOf(IllegalArgumentException.class);
  }
}
