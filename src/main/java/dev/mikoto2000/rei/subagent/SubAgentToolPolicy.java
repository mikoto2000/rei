package dev.mikoto2000.rei.subagent;

import java.util.*;

/** YAML requests capabilities; only this audited allowlist grants them. No MCP or delegation. */
public final class SubAgentToolPolicy {
  private static final Set<String> ALLOWED = Set.of("readMultiFile", "grepMultiQuery", "searchAndRead",
      "readPdfFile", "webSearch", "webSearchAndRead", "searchKnowledge");
  private final Set<String> known;
  public SubAgentToolPolicy(Set<String> known) { this.known = Set.copyOf(known); }
  public List<String> effectiveTools(List<String> requested) {
    return requested.stream().filter(known::contains).filter(ALLOWED::contains).distinct().toList();
  }
  public void validate(List<String> requested) {
    for (String tool : requested) {
      if (!known.contains(tool)) throw new IllegalArgumentException("tools: unknown tool " + tool);
      if (!ALLOWED.contains(tool)) throw new IllegalArgumentException("tools: " + tool + " not permitted for subagents");
    }
  }
}
