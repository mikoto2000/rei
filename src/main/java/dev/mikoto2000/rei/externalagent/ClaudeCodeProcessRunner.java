package dev.mikoto2000.rei.externalagent;

import java.util.*;

/** Keep CLI subscription login/OAuth; do not let inherited API/cloud credentials change the billing route. */
final class ClaudeCodeProcessRunner extends ExternalAgentProcessRunner {
 @Override protected void configureEnvironment(Map<String,String> environment){environment.keySet().removeIf(name->{String upper=name.toUpperCase(Locale.ROOT);return upper.startsWith("ANTHROPIC_")||upper.startsWith("CLAUDE_CODE_USE_")||upper.equals("CLAUDE_CODE_SIMPLE")||upper.equals("CLAUDE_CODE_API_KEY_HELPER_TTL_MS");});environment.put("CLAUDE_CODE_SAFE_MODE","1");}
}
