package dev.mikoto2000.rei.core.completion;

import java.util.*;

/** Usable directly as picocli completionCandidates without coupling this API to picocli. */
public interface CompletionMetadata extends Iterable<String> {
  Set<String> types(CompletionContext context);
  default List<CompletionCandidate> choices(CompletionContext context) {
    var values = new ArrayList<CompletionCandidate>();
    for (String value : this) values.add(CompletionCandidate.value(value, "value"));
    return values;
  }
  @Override default Iterator<String> iterator() { return Collections.emptyIterator(); }
}
