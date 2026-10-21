package dev.mikoto2000.rei.externalagent;

import java.util.Locale;
import java.util.Map;

/**
 * 外部エージェントへの委譲に必要なClaudeコードプロセス実行の担当を扱う。
 *
 * <p>Keep CLI subscription login/OAuth; do not let inherited API/cloud credentials change the
 * billing route.
 */
final class ClaudeCodeProcessRunner extends ExternalAgentProcessRunner {
  /** 外部エージェントへの委譲に関する設定実行環境を処理する。 */
  @Override
  protected void configureEnvironment(Map<String, String> environment) {
    environment
        .keySet()
        .removeIf(
            name -> {
              String upper = name.toUpperCase(Locale.ROOT);
              return upper.startsWith("ANTHROPIC_")
                  || upper.startsWith("CLAUDE_CODE_USE_")
                  || upper.equals("CLAUDE_CODE_SIMPLE")
                  || upper.equals("CLAUDE_CODE_API_KEY_HELPER_TTL_MS");
            });
    environment.put("CLAUDE_CODE_SAFE_MODE", "1");
  }
}
