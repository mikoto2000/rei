package dev.mikoto2000.rei.core.completion;

import java.util.List;

/** Implementations must be bounded, read-only and must never execute tools or contact an LLM. */
public interface CompletionProvider {
  default int priority() { return 100; }
  boolean supports(CompletionContext context);
  List<CompletionCandidate> complete(CompletionContext context);
}
