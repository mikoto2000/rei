package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

class DisabledCompressionSafetyTest {
  @TempDir Path dir;
  @Test void disabledCompressionStillRejectsOversizedFinalInput() {
    var config = new ContextCompressionProperties();
    config.setEnabled(false); config.setHardLimit(100);
    var estimator = TokenEstimator.conservative();
    var assembler = new ContextAssembler(config, estimator, new ConversationSummaryRepository(dir),
        new ToolResultCompressor(new RawToolResultStore(dir), estimator, 8000, 2000),
        (previous, messages, budget, prompt) -> { throw new AssertionError("Compression disabled"); }, null, null);
    assertThatThrownBy(() -> assembler.assemble(new Prompt(List.of(new UserMessage("日".repeat(100)))),
        "chat", "run", () -> {})).hasMessageContaining("CONTEXT_HARD_LIMIT");
  }
}
